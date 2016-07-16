import sbt.Project.Initialize
import sbtassembly.AssemblyPlugin.baseAssemblySettings
import scala.util.matching.Regex

def sbtVersionToRepublish = "0.13.12"
def sbtScalaVersion = "2.10.6"

val Deps = config("deps") hide
val AssembleSources = config("assemble-sources") hide
lazy val originalSbtVersion = SettingKey[String]("original-sbt-version")
lazy val publishLocally = SettingKey[Boolean]("publish-locally")

lazy val root = (project in file(".")).
  aggregate(sbtInterface, compilerInterface, incrementalCompiler, compilerInterfacePrecompiled).
  settings(
    commonSettings,
    name := "sbt-republish",
    inThisBuild(Seq(
      version := sbtVersionToRepublish,
      organization := "com.typesafe.sbt",
      scalaVersion := sbtScalaVersion
    )),
    publishArtifact := false,
    publishArtifact in makePom := false,
    publish := ()
  )

lazy val sbtInterface = (project in file("sbt-interface")).
  configs(Deps, AssembleSources).
  settings(
    commonSettings,
    name := "sbt-interface",
    libraryDependencies <+= originalSbtVersion { "org.scala-sbt" % "interface" % _ % Deps.name },
    libraryDependencies <+= originalSbtVersion { "org.scala-sbt" % "interface" % _ % (AssembleSources.name+"->sources") },
    packageBin in Compile <<= repackageDependency(packageBin, new Regex("interface.*")),
    packageSrc in Compile <<= repackageDependency(packageSrc, new Regex("interface.*"), AssembleSources, updateClassifiers, Some(Set("src")))
  )

lazy val compilerInterface = (project in file("compiler-interface")).
 settings(
  commonSettings,
  name := "compiler-interface",
  libraryDependencies <+= originalSbtVersion { v =>
    ("org.scala-sbt" % "compiler-interface" % v % Deps.name).sources()
  },
  packageSrc in Compile <<= repackageDependency(packageSrc, new Regex("""compiler\-interface.*\-sources""")),
  publishArtifact in packageBin := false,
  publishArtifact in (Compile, packageSrc) := true,
  classpathTypes += "src"
)

lazy val compilerInterfacePrecompiled = (project in file("compiler-interface-precompiled")).
  dependsOn(sbtInterface).
  settings(
    commonSettings,
    name := "compiler-interface-precompiled",
    libraryDependencies <++= originalSbtVersion { v =>
      Seq(("org.scala-sbt" % "compiler-interface" % v % Deps.name).withSources())
    },
    packageBin in Compile <<= repackageDependency(packageBin, new Regex("""compiler\-interface.*""")),
    packageSrc in Compile <<= repackageDependency(packageSrc, new Regex("""compiler\-interface.*\-sources""")),
    classpathTypes += "src"
  )

lazy val incrementalCompiler = (project in file("incremental-compiler")).
  dependsOn(sbtInterface).
  configs(AssembleSources, Deps).
  settings(
    commonSettings,
    name := "incremental-compiler",
    basicAssemblySettings,
    inConfig(AssembleSources)(baseAssemblySettings ++ basicAssemblySettings),
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

lazy val basicAssemblySettings: Seq[Setting[_]] =
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

def repackageDependency(packageTask: TaskKey[File],
                        jarNamePattern: Regex,
                        config: Configuration = Deps,
                        updateTask: TaskKey[UpdateReport] = update,
                        optTypes: Option[Set[String]] = None): Initialize[Task[File]] = {
  (classpathTypes, updateTask, artifactPath in packageTask in Compile) map {
    (types, up, packaged) => {
      val realTypes = optTypes getOrElse types
      val cp = Classpaths.managedJars(config, realTypes, up)
      def cantFindError: Nothing =
        sys.error("Unable to find jar with name: " + jarName + " in " + cp.mkString("\n\t", "\n\t", "\n"))
      val jar = cp.find { x =>
        (jarNamePattern findFirstIn x.data.getName).isDefined
      }.getOrElse(cantFindError).data
      IO.copyFile(jar, packaged, false)
      packaged
    }
  }
}

lazy val commonSettings = Seq(
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
  // credentials += Credentials(Path.userHome / ".ivy2" / "sonatype-credentials"),
  publishArtifact in Test := false,
  homepage := Some(url("http://www.scala-sbt.org/")),
  licenses := Seq("BSD-style" -> url("http://www.opensource.org/licenses/bsd-license.php")),
  scmInfo := Some(ScmInfo(url("https://github.com/typesafehub/sbt-republish"), "git@github.com:typesafehub/sbt-republish.git")),
  developers := List(
    Developer("harrah", "Mark Harrah", "@harrah", url("https://github.com/harrah")),
    Developer("eed3si9n", "Eugene Yokota", "@eed3si9n", url("https://github.com/eed3si9n")),
    Developer("jsuereth", "Josh Suereth", "@jsuereth", url("https://github.com/jsuereth")),
    Developer("dwijnand", "Dale Wijnand", "@dwijnand", url("https://github.com/dwijnand")),
    Developer("gkossakowski", "Grzegorz Kossakowski", "@gkossakowski", url("https://github.com/gkossakowski")),
    Developer("Duhemm", "Martin Duhem", "@Duhemm", url("https://github.com/Duhemm"))
  ),
  pomIncludeRepository := { _ => false },
  ivyConfigurations += Deps,
  externalResolvers <<= (resolvers, publishLocally) map { (rs, local) => if (local) Seq(Resolver.defaultLocal) ++ rs else rs }
)

def environment(property: String, env: String): Option[String] =
  Option(System.getProperty(property)) orElse Option(System.getenv(env))
def ReleaseRepository = "https://oss.sonatype.org/service/local/staging/deploy/maven2"
def SnapshotRepository = "https://oss.sonatype.org/content/repositories/snapshots"
