
ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.11"

ThisBuild / organization := "org.example"

lazy val root = (project in file("."))
  .settings(
      name := "transform",
  )

val scalaVersion_ = "2.13.11"

libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion_ % Provided,
    "org.scala-lang" % "scala-reflect" % scalaVersion_,
    "org.morphir" %% "morphir-ir" % "0.17.0"
)

publishLocal := {
    (assembly in Compile).value // This produces the fat jar
    publishLocal.value // This runs the original publishLocal task
}

// Define a custom artifact
artifact in(Compile, assembly) := {
    val art = (artifact in(Compile, packageBin)).value
    art.withType("jar").withExtension("jar").withClassifier(Some("assembly"))
}

// Add assemblyArtifact to the list of artifacts
addArtifact(artifact in(Compile, assembly), assembly)
