package codacy.eslint

import java.nio.file.Path
import codacy.dockerApi._
import codacy.dockerApi.utils.CommandRunner
import play.api.libs.json._
import seedtools.{FileHelper, ToolHelper}
import scala.util.Try

import play.api.libs.functional.syntax._

case class WarnResult(ruleId: String, message: String, line: JsValue)

object WarnResult {
  implicit val warnReads = (
    (__ \ "ruleId").read[String] and
      (__ \ "message").read[String] and
      (__ \ "line").read[JsValue]
    )(WarnResult.apply _)
}

object ESLint extends Tool {

  override def apply(path: Path, conf: Option[Seq[PatternDef]], files: Option[Set[Path]])(implicit spec: Spec): Try[Iterable[Result]] = {
    Try {
      val filesToLint: Seq[String] = files.fold(Seq(path.toString)) {
        paths =>
          paths.map(_.toString).toSeq
      }

      val patternsToLint = ToolHelper.getPatternsToLint(conf)
      val configuration = Seq("-c", writeConfigFile(patternsToLint))

      val command = Seq("eslint", "-f", "json") ++ configuration ++ filesToLint

      CommandRunner.exec(command) match {
        case Right(resultFromTool) =>
          parseToolResult(resultFromTool.stdout, path)
        case Left(failure) => throw failure
      }
    }
  }

  def parameterToConfig(parameter: ParameterDef): String = {
    parameter.value match {
      case JsString(value) => value
      case other => Json.stringify(other)
    }
  }

  def patternToConfig(pattern: PatternDef): String = {
    val patternId = pattern.patternId.value
    val warnLevel = 1
    val paramConfig = pattern.parameters.fold("") {
      params =>
        val comma = if(params.nonEmpty) "," else ""
        s"""$comma${params.toSeq.map(parameterToConfig).mkString(",")}"""
    }

    s""""$patternId":[$warnLevel$paramConfig]"""
  }

  def writeConfigFile(patternsToLint: Seq[PatternDef]): String = {
    val rules : Seq[String] = patternsToLint.map(patternToConfig)
    val env : Seq[String] = Seq(""""browser":true""")

    val content = s"""{"rules":{${rules.mkString(",")}},"env":{${env.mkString(",")}}}"""

    FileHelper.createTmpFile(content, "config", ".json").toString
  }

  def extractIssuesAndErrors(filePath: String, messages: Option[JsArray])(implicit basePath: String): Seq[Result] = {

    messages.map(
      messagesArr =>
        messagesArr.value.flatMap {
          message =>
            val isFatal = (message \ "fatal").asOpt[Boolean].getOrElse(false)
            if (isFatal) {
              val path = SourcePath(FileHelper.stripPath(filePath, basePath))
              val msg = (message \ "message").asOpt[String].getOrElse("Fatal Error")
              val patternId = PatternId("fatal")
              val line = ResultLine((message \ "line").asOpt[Int].getOrElse(1))

              val fileError = FileError(path, Some(ErrorMessage(msg)))
              val issue = Issue(path,ResultMessage(msg), patternId, line)

              Seq(fileError, issue)
            }
            else {
              message.asOpt[WarnResult].map {
                warn =>
                  Issue(SourcePath(FileHelper.stripPath(filePath, basePath)), ResultMessage(warn.message), PatternId(warn.ruleId), ResultLine(warn.line.asOpt[Int].getOrElse(1)))
              }.toSeq
            }
        }
    ).getOrElse(Seq())
  }

  def resultFromToolResult(toolResult: JsArray)(implicit basePath:String): Seq[Result] = {
    toolResult.value.flatMap {
      fileResult =>
        (fileResult \ "filePath").asOpt[String].map {
          case filePath =>
            val messages = (fileResult \ "messages").asOpt[JsArray]
            extractIssuesAndErrors(filePath, messages)
        }.getOrElse(Seq())
    }
  }

  def parseToolResult(resultFromTool: Seq[String], path: Path): Iterable[Result] = {
    implicit val basePath = path.toString

    val jsonParsed = Json.parse(resultFromTool.mkString)
    jsonParsed.asOpt[JsArray].fold(Seq[Result]())(resultFromToolResult)
  }

}