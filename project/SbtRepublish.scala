import sbt._
import sbt.Keys._
import sbtassembly.Plugin._
import sbtassembly.Plugin.AssemblyKeys._
import sbt.Project.Initialize

object SbtRepublish extends Build {

  val ReleaseRepository = "https://oss.sonatype.org/service/local/staging/deploy/maven2"
  val SnapshotRepository = "https://oss.sonatype.org/content/repositories/snapshots"

  val Deps = config("deps") hide
  val AssembleSources = config("assemble-sources") hide

  val originalSbtVersion = SettingKey[String]("original-sbt-version")
  val publishLocally = SettingKey[Boolean]("publish-locally")

  lazy val buildSettings = Seq(
    organization := "com.typesafe.sbt",
    version := "0.13.6-SNAPSHOT",
    scalaVersion := "2.10.4",
    originalSbtVersion <<= version { v => if (v.endsWith("SNAPSHOT")) "latest.integration" else v },
    resolvers <++= version { v => if (v.endsWith("SNAPSHOT")) Seq(Classpaths.typesafeSnapshots) else Seq.empty },
    resolvers += Classpaths.typesafeReleases,
    resolvers += DefaultMavenRepository,
    crossPaths := false,
    publishMavenStyle := true,
    publishLocally := false,
    publishTo <<= (version, publishLocally) { (v, local) =>
      if (local) Some(Resolver.file("m2", Path.userHome / ".m2" / "repository"))
      else if (v.endsWith("SNAPSHOT")) Some("snapshots" at SnapshotRepository)
      else Some("releases" at ReleaseRepository)
    },
    credentials += Credentials(Path.userHome / ".ivy2" / "sonatype-credentials"),
    publishArtifact in Test := false,
    homepage := Some(url("https://github.com/sbt/sbt")),
    licenses := Seq("BSD-style" -> url("http://www.opensource.org/licenses/bsd-license.php")),
    pomExtra := {
      <scm>
        <url>https://github.com/typesafehub/sbt-republish</url>
        <connection>scm:git:git@github.com:typesafehub/sbt-republish.git</connection>
      </scm>
      <developers>
        <developer>
          <id>harrah</id>
          <name>Mark Harrah</name>
          <url>https://github.com/harrah</url>
        </developer>
        <developer>
          <id>pvlugter</id>
          <name>Peter Vlugter</name>
          <url>https://github.com/pvlugter</url>
        </developer>
      </developers>
    },
    pomIncludeRepository := { _ => false },
    ivyConfigurations += Deps,
    externalResolvers <<= (resolvers, publishLocally) map { (rs, local) => if (local) Seq(Resolver.defaultLocal) ++ rs else rs }
  )

  lazy val sbtRepublish = Project(
    "sbt-republish",
    file("."),
    aggregate = Seq(sbtInterface, compilerInterface, incrementalCompiler, compilerInterfacePrecompiled, sbtLauncher, sbtLaunchInterface),
    settings = buildSettings ++ Seq(
      publishArtifact := false,
      publishArtifact in makePom := false
    )
  )

  lazy val sbtInterface = Project(
    "sbt-interface",
    file("sbt-interface"),
    settings = buildSettings ++ Seq(
      libraryDependencies <+= originalSbtVersion { "org.scala-sbt" % "interface" % _ % Deps.name },
      libraryDependencies <+= originalSbtVersion { "org.scala-sbt" % "interface" % _ % (AssembleSources.name+"->sources") },
      packageBin in Compile <<= repackageDependency(packageBin, "interface"),
      packageSrc in Compile <<= repackageDependency(packageSrc, "interface", AssembleSources, updateClassifiers, Some(Set("src")))
    )
  ).configs(Deps, AssembleSources)


  lazy val sbtLaunchInterface = Project(
    "sbt-launcher-interface",
    file("sbt-launcher-interface"),
    settings = buildSettings ++ Seq(
      libraryDependencies <+= originalSbtVersion { "org.scala-sbt" % "launcher-interface" % _ % Deps.name },
      libraryDependencies <+= originalSbtVersion { "org.scala-sbt" % "launcher-interface" % _ % (AssembleSources.name+"->sources") },
      packageBin in Compile <<= repackageDependency(packageBin, "launcher-interface"),
      packageSrc in Compile <<= repackageDependency(packageSrc, "launcher-interface", AssembleSources, updateClassifiers, Some(Set("src")))
    )
  ).configs(Deps, AssembleSources)


  lazy val sbtLauncher = Project(
    "sbt-launcher",
    file("sbt-launcher"),
    settings = buildSettings ++ Seq(
      libraryDependencies += {
        val launchJarUrl = s"http://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/${originalSbtVersion.value}/sbt-launch.jar"
        "org.scala-sbt" % "sbt-launch" % originalSbtVersion.value % Deps.name from launchJarUrl
      },
      packageBin in Compile <<= repackageDependency(packageBin, "sbt-launch")
    )
  ).configs(Deps)


  lazy val compilerInterface = Project(
    "compiler-interface",
    file("compiler-interface"),
    settings = buildSettings ++ Seq(
      libraryDependencies <+= originalSbtVersion { v =>
        ("org.scala-sbt" % "compiler-interface" % v % Deps.name).artifacts(Artifact("compiler-interface-src"))
      },
      packageSrc in Compile <<= repackageDependency(packageSrc, "compiler-interface-src"),
      publishArtifact in packageBin := false,
      publishArtifact in (Compile, packageSrc) := true
    )
  )


  lazy val compilerInterfacePrecompiled = Project(
    "compiler-interface-precompiled",
    file("compiler-interface-precompiled"),
    dependencies = Seq(sbtInterface),
    settings = buildSettings ++ Seq(
      libraryDependencies <++= originalSbtVersion { v =>
         Seq(("org.scala-sbt" % "compiler-interface" % v % Deps.name).artifacts(Artifact("compiler-interface-bin")),
             ("org.scala-sbt" % "compiler-interface" % v % Deps.name).artifacts(Artifact("compiler-interface-src")))
      },
      packageBin in Compile <<= repackageDependency(packageBin, "compiler-interface-bin"),
      packageSrc in Compile <<= repackageDependency(packageSrc, "compiler-interface-src")
    )
  )

  val basicAssemblySettings: Seq[Setting[_]] =
    Seq(
      assembleArtifact in packageScala := false,
      excludedJars in assembly <<= (fullClasspath in assembly) map { cp =>
        cp filter { jar =>
          val name = jar.data.getName
          name.startsWith("scala-") || name.startsWith("interface-")
        }
      },
      mergeStrategy in assembly <<= (mergeStrategy in assembly)( default => {
        case "NOTICE" => MergeStrategy.first
        case x => default(x)
      })
    )

  lazy val incrementalCompiler = Project(
    "incremental-compiler",
    file("incremental-compiler"),
    dependencies = Seq(sbtInterface),
    settings = buildSettings ++ assemblySettings ++ basicAssemblySettings ++
              inConfig(AssembleSources)(assemblySettings ++ basicAssemblySettings) ++ Seq(
      libraryDependencies <+= originalSbtVersion { "org.scala-sbt" % "compiler-integration" % _ % Deps.name },
      // Since sources aren't transitive here, we may need to be more clever on how we pull these in.  I.e. we pull in everything, so transitive deps come too,
      // and then use the Classpaths.managedJars method to filter only src jars.
      libraryDependencies <+= originalSbtVersion { "org.scala-sbt" % "compiler-integration" % _ % (AssembleSources.name + "->*") },
      libraryDependencies <+= scalaVersion { "org.scala-lang" % "scala-compiler" % _ },
      managedClasspath in Deps <<= (classpathTypes, update) map { (types, up) => Classpaths.managedJars(Deps, types, up) },
      managedClasspath in AssembleSources <<= (updateClassifiers) map { (up) => Classpaths.managedJars(AssembleSources, Set("src"), up) },
      fullClasspath in assembly <<= managedClasspath in Deps,
      fullClasspath in assembly in AssembleSources <<= managedClasspath in AssembleSources,
      packageBin in Compile <<= (assembly, artifactPath in packageBin in Compile) map {
        (assembled, packaged) => IO.copyFile(assembled, packaged, false); packaged
      },
      packageSrc in Compile <<= (assembly in AssembleSources, artifactPath in packageSrc in Compile) map {
        (assembled, packaged) => IO.copyFile(assembled, packaged, false); packaged
      },
      jarName in assembly in AssembleSources <<= (name, version) map { (name, version) => name + "-assembly-sources-" + version + ".jar" }
    )
  ).configs(AssembleSources, Deps)

  def repackageDependency(packageTask: TaskKey[File],
                          jarName: String,
                          config: Configuration = Deps,
                          updateTask: TaskKey[UpdateReport] = update,
                          optTypes: Option[Set[String]] = None): Initialize[Task[File]] = {
    (classpathTypes, updateTask, artifactPath in packageTask in Compile) map {
      (types, up, packaged) => {
        val realTypes = optTypes getOrElse types
        val cp = Classpaths.managedJars(config, realTypes, up)
        def cantFindError: Nothing =
          sys.error("Unable to find jar with name: " + jarName + " in " + cp.mkString("\n\t", "\n\t", "\n"))
        val jar = cp.find(_.data.getName startsWith jarName).getOrElse(cantFindError).data
        IO.copyFile(jar, packaged, false)
        packaged
      }
    }
  }

  def environment(property: String, env: String): Option[String] =
    Option(System.getProperty(property)) orElse Option(System.getenv(env))
}
