import com.typesafe.sbt.SbtGit.git
import _root_.sbtcrossproject.CrossPlugin.autoImport.CrossType

addCommandAlias("validateJVM", "all scalafmtCheckAll scalafmtSbtCheck testsJVM/test")
addCommandAlias("validateJS", ";testsJS/test")
addCommandAlias("fmt", "all scalafmtSbt scalafmtAll")
addCommandAlias("fmtCheck", "all scalafmtSbtCheck scalafmtCheckAll")
addCommandAlias("gitSnapshots", ";set version in ThisBuild := git.gitDescribedVersion.value.get + \"-SNAPSHOT\"")

val Scala212 = "2.12.12"
val Scala213 = "2.13.4"

// update to scala 3 requires swapping from scalatest to munit and reimplementing all macros
ThisBuild / crossScalaVersions := Seq(Scala212, Scala213 /*, "3.0.0-M1", "3.0.0-M2"*/ )
ThisBuild / scalaVersion := Scala212

ThisBuild / githubWorkflowPublishTargetBranches := Seq()
ThisBuild / githubWorkflowArtifactUpload := false

ThisBuild / githubWorkflowBuildMatrixAdditions +=
  "ci" -> List("validateJS", "validateJVM")

ThisBuild / githubWorkflowBuild := Seq(WorkflowStep.Sbt(List("${{ matrix.ci }}"), name = Some("Validation")))

ThisBuild / githubWorkflowAddedJobs ++= Seq(
  WorkflowJob(
    "coverage",
    "Coverage",
    githubWorkflowJobSetup.value.toList ::: List(
      WorkflowStep.Use("actions", "setup-python", "v2", name = Some("Setup Python")),
      WorkflowStep.Run(List("pip install codecov"), name = Some("Install Codecov")),
      WorkflowStep
        .Sbt(List("coverage", "rootJVM/test", "rootJVM/coverageReport"), name = Some("Calculate test coverage")),
      WorkflowStep.Run(List("codecov"), name = Some("Upload coverage results"))
    ),
    scalas = crossScalaVersions.value.toList.filter(_.startsWith("2."))
  ),
  WorkflowJob(
    "microsite",
    "Microsite",
    githubWorkflowJobSetup.value.toList ::: List(
      WorkflowStep.Use(
        "ruby",
        "setup-ruby",
        "v1",
        name = Some("Setup Ruby"),
        params = Map("ruby-version" -> "2.6", "bundler-cache" -> "true")
      ),
      WorkflowStep.Run(List("gem install jekyll -v 2.5"), name = Some("Install Jekyll")),
      WorkflowStep.Sbt(List("docs/makeMicrosite"), name = Some("Build microsite"))
    ),
    scalas = List(Scala212)
  )
)

lazy val libs = org.typelevel.libraries
  .add("discipline-scalatest", version = "2.1.0", org = org.typelevel.typeLevelOrg)
  .add("cats", version = "2.3.0")
  .add("paradise", version = "2.1.1")
  .add("circe-core", version = "0.13.0", org = "io.circe")

val apache2 = "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")
val gh = GitHubSettings(org = "typelevel", proj = "cats-tagless", publishOrg = "org.typelevel", license = apache2)

lazy val rootSettings = buildSettings ++ commonSettings ++ publishSettings
lazy val module = mkModuleFactory(gh.proj, mkConfig(rootSettings, commonJvmSettings, commonJsSettings))
lazy val prj = mkPrjFactory(rootSettings)

lazy val `cats-tagless` = project
  .configure(mkRootConfig(rootSettings, rootJVM))
  .aggregate(rootJVM, rootJS, docs)
  .dependsOn(rootJVM, rootJS)
  .settings(noPublishSettings, crossScalaVersions := Seq(Scala212))

lazy val rootJVM = project
  .configure(mkRootJvmConfig(gh.proj, rootSettings, commonJvmSettings))
  .aggregate(coreJVM, lawsJVM, testsJVM, macrosJVM)
  .dependsOn(coreJVM, lawsJVM, testsJVM, macrosJVM)
  .settings(noPublishSettings, crossScalaVersions := Seq(Scala212))

lazy val rootJS = project
  .configure(mkRootJsConfig(gh.proj, rootSettings, commonJsSettings))
  .aggregate(coreJS, lawsJS, testsJS, macrosJS)
  .dependsOn(coreJS, lawsJS, testsJS, macrosJS)
  .settings(noPublishSettings, crossScalaVersions := Seq(Scala212))

lazy val core = prj(coreM)
lazy val coreJVM = coreM.jvm
lazy val coreJS = coreM.js
lazy val coreM = module("core", CrossType.Pure)
  .settings(libs.dependency("cats-core"))
  .settings(simulacrumSettings(libs))
  .enablePlugins(AutomateHeaderPlugin)

lazy val laws = prj(lawsM)
lazy val lawsJVM = lawsM.jvm
lazy val lawsJS = lawsM.js
lazy val lawsM = module("laws", CrossType.Pure)
  .dependsOn(coreM)
  .settings(libs.dependency("cats-laws"))
  .settings(disciplineDependencies)
  .enablePlugins(AutomateHeaderPlugin)

lazy val macros = prj(macrosM)
lazy val macrosJVM = macrosM.jvm
lazy val macrosJS = macrosM.js
lazy val macrosM = module("macros", CrossType.Pure)
  .dependsOn(coreM)
  .aggregate(coreM)
  .settings(scalaMacroDependencies(libs))
  .settings(macroAnnotationsSettings)
  .settings(copyrightHeader)
  .settings(
    libs.testDependencies("scalatest", "scalacheck"),
    doctestTestFramework := DoctestTestFramework.ScalaCheck
  )
  .enablePlugins(AutomateHeaderPlugin)

lazy val tests = prj(testsM)
lazy val testsJVM = testsM.jvm
lazy val testsJS = testsM.js
lazy val testsM = module("tests", CrossType.Pure)
  .dependsOn(macrosM, lawsM)
  .settings(
    libs.testDependencies(
      "shapeless",
      "scalatest",
      "cats-free",
      "cats-effect",
      "cats-testkit",
      "discipline-scalatest",
      "circe-core"
    ),
    scalacOptions in Test := (scalacOptions in Test).value.filter(_ != "-Xfatal-warnings"),
    scalaMacroDependencies(libs),
    macroAnnotationsSettings,
    noPublishSettings
  )
  .enablePlugins(AutomateHeaderPlugin)

/** Docs - Generates and publishes the scaladoc API documents and the project web site. */
lazy val docs = project
  .settings(rootSettings)
  .settings(moduleName := gh.proj + "-docs")
  .settings(noPublishSettings)
  .settings(unidocCommonSettings)
  .settings(commonJvmSettings)
  .settings(scalaMacroDependencies(libs))
  .settings(libs.dependency("cats-free"))
  .dependsOn(List(macrosJVM).map(ClasspathDependency(_, Some("compile;test->test"))): _*)
  .enablePlugins(MicrositesPlugin)
  .enablePlugins(SiteScaladocPlugin)
  .settings(
    crossScalaVersions := Seq(Scala212),
    docsMappingsAPIDir := "api",
    addMappingsToSiteDir(mappings in packageDoc in Compile in coreJVM, docsMappingsAPIDir),
    organization := gh.organisation,
    micrositeCompilingDocsTool := WithTut,
    autoAPIMappings := true,
    micrositeName := "Cats-tagless",
    micrositeDescription := "A library of utilities for tagless final algebras",
    micrositeBaseUrl := "cats-tagless",
    micrositeGithubOwner := "typelevel",
    micrositeGithubRepo := "cats-tagless",
    micrositeHighlightTheme := "atom-one-light",
    fork in tut := true,
    micrositePalette := Map(
      "brand-primary" -> "#51839A",
      "brand-secondary" -> "#EDAF79",
      "brand-tertiary" -> "#96A694",
      "gray-dark" -> "#192946",
      "gray" -> "#424F67",
      "gray-light" -> "#E3E2E3",
      "gray-lighter" -> "#F4F3F4",
      "white-color" -> "#FFFFFF"
    ),
    ghpagesNoJekyll := false,
    micrositeAuthor := "cats-tagless Contributors",
    scalacOptions in Tut ~= (_.filterNot(Set("-Ywarn-unused-import", "-Ywarn-unused:imports", "-Ywarn-dead-code"))),
    git.remoteRepo := gh.repo,
    includeFilter in makeSite := "*.html" | "*.css" | "*.png" | "*.jpg" | "*.gif" | "*.js" | "*.swf" | "*.yml" | "*.md"
  )

lazy val docsMappingsAPIDir = settingKey[String]("Name of subdirectory in site target directory for api docs")

lazy val buildSettings = sharedBuildSettings(gh, libs)

lazy val commonSettings = sharedCommonSettings ++ Seq(
  crossScalaVersions := (ThisBuild / crossScalaVersions).value,
  parallelExecution in Test := false,
  resolvers ++= Seq(Resolver.sonatypeRepo("releases"), Resolver.sonatypeRepo("snapshots")),
  addCompilerPlugin(("org.typelevel" % "kind-projector" % "0.11.2").cross(CrossVersion.full)),
  developers := List(
    Developer(
      "Georgi Krastev",
      "@joroKr21",
      "joro.kr.21@gmail.com",
      new java.net.URL("https://www.linkedin.com/in/georgykr")
    ),
    Developer("Kailuo Wang", "@kailuowang", "kailuo.wang@gmail.com", new java.net.URL("http://kailuowang.com")),
    Developer(
      "Luka Jacobowitz",
      "@LukaJCB",
      "luka.jacobowitz@fh-duesseldorf.de",
      new java.net.URL("http://stackoverflow.com/users/3795501/luka-jacobowitz")
    )
  )
) ++ scalacAllSettings ++ unidocCommonSettings ++ copyrightHeader

lazy val commonJsSettings = Seq(
  scalaJSStage in Global := FastOptStage,
  // currently sbt-doctest doesn't work in JS builds
  // https://github.com/tkawachi/sbt-doctest/issues/52
  doctestGenTests := Seq.empty
)

lazy val commonJvmSettings = scoverageSettings

lazy val publishSettings = sharedPublishSettings(gh) ++ credentialSettings ++ sharedReleaseProcess

lazy val scoverageSettings = sharedScoverageSettings(60)

lazy val disciplineDependencies = libs.dependencies("discipline-core", "scalacheck")

lazy val copyrightHeader = Seq(
  startYear := Some(2019),
  organizationName := "cats-tagless maintainers"
)
