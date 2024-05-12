// Full service, batteries-included, let's go!
addSbtPlugin("org.typelevel" % "sbt-typelevel" % "0.6.7")

// Set me up for CI release, but don't touch my scalacOptions!
addSbtPlugin("org.typelevel" % "sbt-typelevel-ci-release" % "0.6.7")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.16.0")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.2.0")
