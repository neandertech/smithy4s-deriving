import org.eclipse.jgit.api.MergeCommand.FastForwardMode.Merge
ThisBuild / tlBaseVersion := "0.0" // your current series x.y
ThisBuild / version := {
  if (!sys.env.contains("CI")) "0.0.0-SNAPSHOT"
  else (ThisBuild / version).value
}

ThisBuild / organization := "tech.neander"
ThisBuild / organizationName := "Neandertech"
ThisBuild / startYear := Some(2024)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers ++= List(
  // your GitHub handle and name
  tlGitHubDev("baccata", "Olivier Mélois")
)

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))

ThisBuild / scalacOptions ++= Seq(
  "-language:implicitConversions"
)

ThisBuild / githubWorkflowBuildPreamble += {
  import org.typelevel.sbt.gha.*
  import WorkflowStep.*

  val paths =
    List(
      "~/Library/Caches/sbt-vcpkg/vcpkg-install",
      "~/.cache/sbt-vcpkg/vcpkg-install",
      "~/.cache/sbt-vcpkg/vcpkg"
    )

  Use(
    UseRef.Public("actions", "cache", "v4"),
    name = Some("Cache vcpkg"),
    params = Map("key" -> "${{ runner.os }}-sbt-vcpkg", "path" -> paths.mkString("\n"))
  )
}

val Scala3 = "3.4.1"
ThisBuild / scalaVersion := Scala3 // the default Scala

testFrameworks += new TestFramework("munit.Framework")

val smithyVersion = "1.47.0"
val smithy4sVersion = "0.18.23"
val alloyVersion = "0.3.7"

lazy val root = tlCrossRootProject.aggregate(core, examples, plugin, pluginBundle, tests)

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/core"))
  .settings(
    name := "smithy4s-deriving",
    description := "Derivation for smithy4s-construct",
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %%% "smithy4s-core" % smithy4sVersion
    )
  )

lazy val plugin = project
  .in(file("modules/compiler-plugin"))
  .dependsOn(core.jvm)
  .enablePlugins(AssemblyPlugin)
  .settings(
    name := "smithy4s-deriving-compiler-plugin",
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
      "io.github.classgraph" % "classgraph" % "4.8.172",
      "com.disneystreaming.smithy4s" %% "smithy4s-dynamic" % smithy4sVersion,
      "com.disneystreaming.alloy" % "alloy-core" % alloyVersion
    ),
    // ASSEMBLY
    assembly / logLevel := Level.Debug,
    assemblyPackageScala / assembleArtifact := false,
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      cp.filter { x => x.data.getName.startsWith("scala3-") || x.data.getName.startsWith("jline") }
    },
    assemblyMergeStrategy := {
      case PathList("META-INF", "smithy", "manifest") => MergeStrategy.concat
      case PathList("META-INF", "services", _)        => MergeStrategy.concat
      case PathList("plugin.properties")              => MergeStrategy.last
      case x =>
        val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )

lazy val pluginBundle = project
  .in(file("modules/bundle"))
  .enablePlugins(AssemblyPlugin)
  .settings(
    name := "smithy4s-deriving-compiler",
    Compile / packageBin := (plugin / assembly).value
  )

lazy val tests = crossProject(JVMPlatform)
  .in(file("modules/tests"))
  .enablePlugins(NoPublishPlugin)
  .dependsOn(core)
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "0.7.29" % Test,
      "com.disneystreaming.smithy4s" %%% "smithy4s-dynamic" % "0.18.16" % Test,
      "software.amazon.smithy" % "smithy-build" % smithyVersion % Test,
      "software.amazon.smithy" % "smithy-diff" % smithyVersion % Test
    )
  )

lazy val examples = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/examples"))
  .dependsOn(core)
  .enablePlugins(NoPublishPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %%% "smithy4s-http4s" % smithy4sVersion,
      "com.disneystreaming.smithy4s" %%% "smithy4s-dynamic" % smithy4sVersion,
      "org.http4s" %%% "http4s-ember-client" % "0.23.26",
      "org.http4s" %%% "http4s-ember-server" % "0.23.26",
      // This particular version of fs2-io performs evictions of core CE and
      // fs2 libraries so that networking with Ember works out of the box
      // It still requires all necessary native dependencies (openssl, s2n, zlib)
      // to be installed globally
      "co.fs2" %%% "fs2-io" % "3.10-365636d"
    ),
    autoCompilerPlugins := true,
    Compile / fork := true,
    Compile / scalacOptions += {
      val pluginClasspath =
        (plugin / Compile / fullClasspathAsJars).value.map(_.data.getAbsolutePath()).mkString(":")
      s"""-Xplugin:$pluginClasspath"""
    }
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "software.amazon.smithy" % "smithy-model" % smithyVersion
    )
  )
  .nativeEnablePlugins(VcpkgNativePlugin)
  .nativeSettings(
    vcpkgDependencies := VcpkgDependencies(
      "curl",
      "s2n",
      "openssl"
    ),
    vcpkgNativeConfig ~= {
      _.withRenamedLibraries(
        Map("curl" -> "libcurl")
      )
    },
    Test / fork := false
  )
  .jsSettings(
    Test / fork := false
  )
