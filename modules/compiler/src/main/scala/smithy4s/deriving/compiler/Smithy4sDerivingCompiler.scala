package smithy4s.deriving.compiler

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.report
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.plugins.StandardPlugin
import dotty.tools.backend.jvm.GenBCode
import scala.jdk.CollectionConverters._
import io.github.classgraph.ClassGraph
import smithy4s.dynamic.DynamicSchemaIndex
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ModelSerializer
import java.net.URLClassLoader
import scala.util.control.NonFatal
import dotty.tools.dotc.CompilationUnit
import io.github.classgraph.ClassRefTypeSignature
import software.amazon.smithy.model.validation.ValidationEvent
import software.amazon.smithy.model.validation.Severity

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
      val events = Model
        .assembler(this.getClass().getClassLoader())
        .discoverModels(this.getClass().getClassLoader())
        .addDocumentNode(node)
        .assemble()
        .getValidationEvents()
        .asScala
      events.foreach(reportEvent)
    } finally {
      scanResult.close()
    }
    result
  }

  private def reportEvent(event: ValidationEvent)(using Context): Unit = {
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
    );
    event.getSeverity() match
      case Severity.SUPPRESSED => report.inform(formatted)
      case Severity.NOTE       => report.inform(formatted)
      case Severity.WARNING    => report.warning(formatted)
      case Severity.DANGER     => report.error(formatted)
      case Severity.ERROR      => report.error(formatted)
  }

}

object Smithy4sDerivingCompilerPhase {
  val name = "smithy4s-deriving-compiler-phase"
}
