package smithy4s.deriving.compiler

import dotty.tools.backend.jvm.GenBCode
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.plugins.StandardPlugin
import dotty.tools.dotc.report
import dotty.tools.dotc.util.NoSourcePosition
import dotty.tools.dotc.util.Spans
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassRefTypeSignature
import smithy4s.Document
import smithy4s.deriving.internals.SourcePosition
import smithy4s.dynamic.DynamicSchemaIndex
import smithy4s.dynamic.NodeToDocument
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ModelSerializer
import software.amazon.smithy.model.shapes.ShapeId as SmithyShapeId
import software.amazon.smithy.model.validation.Severity
import software.amazon.smithy.model.validation.ValidationEvent

import java.net.URLClassLoader
import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.control.NonFatal

class Smithy4sDerivingCompiler extends StandardPlugin {
  val name: String = "smithy4s-deriving-compiler"
  override val description: String = "Runs smithy linting on derived constructs"
  override def init(options: List[String]): List[PluginPhase] =
    List(Smithy4sDerivingCompilerPhase())
}

class Smithy4sDerivingCompilerPhase() extends PluginPhase {

  override def phaseName: String = Smithy4sDerivingCompilerPhase.name
  override val runsAfter = Set(GenBCode.name)
  // Overriding `runOn` instead of `run` because the latter is run per compilation unit (files)
  override def runOn(units: List[CompilationUnit])(using context: Context): List[CompilationUnit] = {

    val result = super.runOn(units)

    val compileClasspath = context.settings.classpath.value
    val output = context.settings.outputDir.value.jpath
    val urls = compileClasspath.split(":").map(new java.io.File(_).toURI().toURL())
    val allUrls = urls.appended(output.toUri().toURL())
    val classLoader = new URLClassLoader(allUrls, this.getClass().getClassLoader())

    val scanResult = new ClassGraph()
      .addClassLoader(classLoader)
      .enableAllInfo()
      .scan()

    try {
      val apiClassInfo = scanResult.getClassInfo("smithy4s.deriving.API")

      val builder = scanResult
        .getClassesImplementing("smithy4s.deriving.API")
        .filter(info => !info.isAbstract())
        .asMap()
        .asScala
        .foldLeft(DynamicSchemaIndex.builder) { case (builder, (name, info)) =>
          try {
            val cls = info.loadClass(true)
            val clsLocation = cls.getProtectionDomain().getCodeSource().getLocation().toURI()
            // checking that the class comes from the current compilation unit
            if (clsLocation == output.toUri()) {
              // Getting the outer class, with the assumption that it'll be the companion object
              // of the class for which an API is derived
              // TODO : add some more protections
              val outer = info.getOuterClasses().get(0)
              val givenAPIMethodInfo = outer
                .getMethodInfo()
                .asScala
                .find { methodInfo =>
                  val sig = methodInfo.getTypeSignature()
                  methodInfo.getParameterInfo().isEmpty && // looking for parameterless methods
                  sig != null &&
                  sig.getResultType().isInstanceOf[ClassRefTypeSignature] &&
                  sig.getResultType().asInstanceOf[ClassRefTypeSignature].getClassInfo() == apiClassInfo
                }

              val companionConstructor = outer.getConstructorInfo().get(0).loadClassAndGetConstructor()
              companionConstructor.setAccessible(true)
              val companion = companionConstructor.newInstance()
              val givenAPIMethod = givenAPIMethodInfo.get.loadClassAndGetMethod()
              val api = givenAPIMethod.invoke(companion).asInstanceOf[smithy4s.deriving.API[?]]
              builder.addService[api.Free]
            } else {
              builder
            }
          } catch {
            case NonFatal(e) =>
              report.error(s"Error when loading ${info.getName()} ${e.getMessage()}")
              e.printStackTrace()
              builder
          }
        }

      val unvalidatedModel = builder.build().toSmithyModel
      val node = ModelSerializer.builder().build().serialize(unvalidatedModel)
      val assemblyResult = Model
        .assembler(this.getClass().getClassLoader())
        .discoverModels(this.getClass().getClassLoader())
        .addDocumentNode(node)
        .assemble()

      val events = assemblyResult.getValidationEvents().asScala
      events.foreach(reportEvent(unvalidatedModel))
    } finally {
      scanResult.close()
    }
    result
  }

  private def reportEvent(model: Model)(event: ValidationEvent)(using context: Context): Unit = {
    var message = event.getMessage()

    val reason = event.getSuppressionReason().orElse(null)
    if (reason != null) { message += " (" + reason + ")" }
    val hint = event.getHint().orElse(null);
    if (hint != null) { message += " [" + hint + "]" }

    val formatted = String.format(
      "%s: %s | %s",
      event.getShapeId().map(_.toString).orElse("-"),
      message,
      event.getId()
    )

    val SourcePositionId = SmithyShapeId.fromParts(SourcePosition.id.namespace, SourcePosition.id.name)
    val sourcePositionDecoder = Document.Decoder.fromSchema(SourcePosition.schema)

    val maybeSourcePos = event
      .getShapeId()
      .flatMap(model.getShape)
      .flatMap(sourcePos => Optional.ofNullable(sourcePos.getAllTraits().get(SourcePositionId)))
      .map(_.toNode())
      .map(NodeToDocument(_))
      .flatMap(sourcePositionDecoder.decode(_).toOption.toJava)
      .toScala

    val scalaPosition = maybeSourcePos match {
      case None => NoSourcePosition
      case Some(pos) =>
        val sourceFile = context.getSource(pos.path)
        dotty.tools.dotc.util.SourcePosition(sourceFile, Spans.Span(pos.start, pos.end))
    }

    event.getSeverity() match
      case Severity.SUPPRESSED => report.inform(formatted, scalaPosition)
      case Severity.NOTE       => report.inform(formatted, scalaPosition)
      case Severity.WARNING    => report.warning(formatted, scalaPosition)
      case Severity.DANGER     => report.error(formatted, scalaPosition)
      case Severity.ERROR      => report.error(formatted, scalaPosition)
  }

}

object Smithy4sDerivingCompilerPhase {
  val name = "smithy4s-deriving-compiler-phase"
}
