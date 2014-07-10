import sbt._
import ActivatorBuild._
import Dependencies._
import Packaging.localRepoArtifacts
import com.typesafe.sbt.S3Plugin._
import com.typesafe.sbt.SbtNativePackager.Universal
import com.typesafe.sbt.SbtPgp
import com.typesafe.sbt.SbtPgp.PgpKeys
import play.PlayImport.PlayKeys
import com.typesafe.sbt.less.Import.LessKeys
import com.typesafe.sbt.web.SbtWeb.autoImport._
import com.typesafe.sbt.jse.JsEngineImport.JsEngineKeys
// NOTE - This file is only used for SBT 0.12.x, in 0.13.x we'll use build.sbt and scala libraries.
// As such try to avoid putting stuff in here so we can see how good build.sbt is without build.scala.


object TheActivatorBuild extends Build {

  def fixFileForURIish(f: File): String = {
    val uriString = f.toURI.toASCIIString
    if(uriString startsWith "file://") uriString.drop("file://".length)
    else uriString.drop("file:".length)
  }

  // ADD sbt launcher support here.
  override def settings = super.settings ++ SbtSupport.buildSettings ++ baseVersions ++ Seq(
    // This is a hack, so the play application will have the right view of the template directory.
    Keys.baseDirectory <<= Keys.baseDirectory apply { bd =>
      sys.props("activator.home") = fixFileForURIish(bd.getAbsoluteFile)
      bd
    }
  ) 
  // TODO : Add ++ play.Project.intellijCommandSettings Play 2.3 style to settings above

  val root = (
    Project("root", file("."))  // TODO - Oddities with clean..
    .noAutoPgp
    .doNotPublish
    aggregate(toReferences(publishedProjects ++
                           Seq(dist, it, localTemplateRepo, offlinetests)): _*)
  )

  lazy val news: Project = (
    Project("news", file("news"))
    settings(NewsHelper.settings:_*)
  )

  // This project helps us isolate creating the local template repository for testing.
  lazy val localTemplateRepo: Project = (
    Project("template-repository", file("template-repository"))
    .noAutoPgp
    .doNotPublish
    settings(LocalTemplateRepo.settings:_*)
    settings(Keys.resolvers += typesafeIvyReleases)
  )

  // These are the projects we want in the local repository we deploy.
  lazy val publishedProjects: Seq[Project] = Seq(ui, uiCommon, launcher, props)

  // basic project that gives us properties to use in other projects.
  lazy val props = (
    ActivatorJavaProject("props")
    settings(Properties.makePropertyClassSetting(Dependencies.sbtVersion, Dependencies.scalaVersion):_*)
  )

  // Helper for UI projects (CLI + GUI)
  lazy val uiCommon = (
    ActivatorProject("ui-common")
    dependsOnRemote(templateCache)
    dependsOn(props)
  )

  val verboseSbtTests = false



  // Helpers to let us grab necessary sbt remote control artifacts, but not actually depend on them at
  // runtime.
  lazy val SbtProbesConfig = config("sbtprobes")
  def makeProbeClasspath(update: sbt.UpdateReport): String = {
    val probeClasspath = update.matching(configurationFilter(SbtProbesConfig.name))
    Path.makeString(probeClasspath)
  }

  def configureSbtTest(testKey: Scoped) = Seq(
    // set up embedded sbt for tests, we fork so we can set
    // system properties.
    Keys.fork in Test in testKey := true,
    Keys.javaOptions in Test in testKey <<= (
      SbtSupport.sbtLaunchJar,
      Keys.javaOptions in testKey,
      Keys.update) map {
      (launcher, oldOptions, updateReport) =>
        oldOptions ++ Seq("-Dsbtrc.no-shims=true",
                          "-Dsbtrc.launch.jar=" + launcher.getAbsoluteFile.getAbsolutePath,
                          "-Dsbtrc.controller.classpath=" + makeProbeClasspath(updateReport)) ++
      (if (verboseSbtTests)
        Seq("-Dakka.loglevel=DEBUG",
            "-Dakka.actor.debug.autoreceive=on",
            "-Dakka.actor.debug.receive=on",
            "-Dakka.actor.debug.lifecycle=on")
       else
         Seq.empty)
    })

  import WebKeys.{assets, public}
  import sbt.Keys.products

  lazy val ui = (
    ActivatorPlayProject("ui")
    dependsOnRemote(
      requirejs, jquery, knockout, ace, /*requireCss, requireText,*/ keymage, commonsIo, mimeUtil, activatorAnalytics,
      herokuApi, herokuJson, herokuHttp, jgit,
      sbtLauncherInterface % "provided",
      sbtrcRemoteController % "compile;test->test",
      // Here we hack our probes into the UI project.
      sbtrcProbe13 % "sbtprobes->default(compile)",
      sbtshimUiInterface13 % "sbtprobes->default(compile)"
    )
    dependsOn(props, uiCommon)
    settings(PlayKeys.playDefaultPort := 8888)
    settings(Keys.includeFilter in (Assets, LessKeys.less) := "*.less")
    settings(Keys.excludeFilter in (Assets, LessKeys.less) := "_*.less")
    settings(Keys.initialize ~= { _ => sys.props("scalac.patmat.analysisBudget") = "512" })
    settings(Keys.libraryDependencies ++= Seq(Dependencies.akkaTestkit % "test", Dependencies.specs2 % "test"))
    // set up debug props for forked tests
    settings(configureSbtTest(Keys.test): _*)
    settings(configureSbtTest(Keys.testOnly): _*)
    // set up debug props for "run"
    settings(
      // Here we hack so that we can see the sbt-rc classes...
      Keys.ivyConfigurations ++= Seq(SbtProbesConfig),
      Keys.update <<= (
          SbtSupport.sbtLaunchJar,
          Keys.update,
          LocalTemplateRepo.localTemplateCacheCreated in localTemplateRepo) map {
        (launcher, update, templateCache) =>
          // We register the location after it's resolved so we have it for running play...
          sys.props("sbtrc.launch.jar") = launcher.getAbsoluteFile.getAbsolutePath
          // The debug variant of the sbt finder automatically splits the ui + controller jars apart.
          sys.props("sbtrc.controller.classpath") = makeProbeClasspath(update)
          sys.props("activator.template.cache") = fixFileForURIish(templateCache)
          sys.props("activator.runinsbt") = "true"
          System.err.println("Updating sbt launch jar: " + sys.props("sbtrc.launch.jar"))
          System.err.println("Remote probe classpath = " + sys.props("sbtrc.controller.classpath"))
          System.err.println("Template cache = " + sys.props("activator.template.cache"))
          update
      },
      // We need to embed the assets in this JAR for activator.
      // If we add any more play projects, we need to be clever with them.
      public in Assets := (public in Assets).value / "public",
      products in Compile += (assets in Assets).value.getParentFile
    )
    settings(
      Keys.compile in Compile <<= (Keys.compile in Compile, Keys.baseDirectory, Keys.streams) map { (oldCompile, baseDir, streams) =>
        val jsErrors = JsChecker.fixAndCheckAll(baseDir, streams.log)
        for (error <- jsErrors) {
          streams.log.error(error)
        }
        if (jsErrors.nonEmpty)
          throw new RuntimeException(jsErrors.length + " JavaScript formatting errors found")
        else
          streams.log.info("JavaScript whitespace meets our exacting standards")
        oldCompile
      }
    )
  )

  lazy val launcher = (
    ActivatorProject("launcher")
    dependsOnRemote(sbtLauncherInterface, sbtCompletion)
    dependsOn(props, uiCommon)
  )

  // A hack project just for convenient IvySBT when resolving artifacts into new local repositories.
  lazy val dontusemeresolvers = (
    ActivatorProject("dontuseme")
    .doNotPublish
    settings(
      // This hack removes the project resolver so we don't resolve stub artifacts.
      Keys.fullResolvers <<= (Keys.externalResolvers, Keys.sbtResolver) map (_ :+ _),
      Keys.resolvers += Resolver.url("sbt-plugin-releases", new URL("http://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/"))(Resolver.ivyStylePatterns)
    )
  )
  lazy val it = (
      ActivatorProject("integration-tests")
      settings(integration.settings:_*)
      dependsOnRemote(sbtLauncherInterface, sbtIo, sbtrcRemoteController)
      dependsOn(props)
      settings(
        org.sbtidea.SbtIdeaPlugin.ideaIgnoreModule := true,
        // we don't use doNotPublish because we want to publishLocal
        Keys.publish := {},
        PgpKeys.publishSigned := {}
      )
  )

  lazy val offlinetests = (
    ActivatorProject("offline-tests")
    .doNotPublish
    settings(offline.settings:_*)
  )

  lazy val logDownloadUrls = taskKey[Unit]("log download urls because we are lazy and don't want to hand-construct them")

  lazy val dist = (
    ActivatorProject("dist")
    // TODO - Should publish be pushing the S3 upload?
    .doNotPublish
    settings(Packaging.settings:_*)
    settings(s3Settings:_*)
    settings(
      Keys.scalaBinaryVersion <<= Keys.scalaBinaryVersion in ui,
      Keys.resolvers ++= Seq(
        "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/",
        Resolver.url("typesafe-ivy-releases", new URL("http://repo.typesafe.com/typesafe/releases/"))(Resolver.ivyStylePatterns),
        Resolver.url("sbt-plugin-releases", new URL("http://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/"))(Resolver.ivyStylePatterns)
      ),
      // TODO - Do this better - This is where we define what goes in the local repo cache.
      localRepoArtifacts <++= (publishedProjects.toSeq map { ref =>
        (Keys.projectID in ref) apply { id => id }
      }).join,
      localRepoArtifacts ++= Seq(

        // base dependencies
        "org.scala-sbt" % "sbt" % Dependencies.sbtVersion,
        "org.scala-lang" % "scala-compiler" % Dependencies.sbtPluginScalaVersion,
        "org.scala-lang" % "scala-compiler" % Dependencies.scalaVersion,

        // sbt stuff
        sbtrcRemoteController,

        // sbt 0.13 plugins
        playSbt13Plugin,
        eclipseSbt13Plugin,
        ideaSbt13Plugin,
        echoSbt13Plugin,
        echoPlaySbt13Plugin,

        // featured template deps
        // *** note: do not use %% here ***
        "com.h2database" % "h2" % "1.3.175",
        "com.novocode" % "junit-interface" % "0.10",
        "com.typesafe.slick" % "slick_2.11" % Dependencies.slickVersion,
        "junit" % "junit" % "4.11",
        "junit" % "junit" % "3.8.1",
        "org.slf4j" % "slf4j-nop" % "1.6.4",
        "org.fusesource.jansi" % "jansi" % "1.11",
        "org.scalatest" % "scalatest_2.11" % "2.1.6",

        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-jshint" % "1.0.0", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-rjs" % "1.0.1", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-digest" % "1.0.0", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-mocha" % "1.0.0", "0.13", "2.10"),
        // reactive maps using an older sbt-gzip, later we should go back to one copy
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-gzip" % "1.0.0", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-gzip" % "1.0.1", "0.13", "2.10"),

        // transient dependencies used in offline mode
        "org.scala-lang" % "jline" % "2.10.4",
        Defaults.sbtPluginExtra("com.typesafe.play" % "sbt-plugin" % Dependencies.playVersion, "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-coffeescript" % "1.0.0", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-less" % "1.0.0", "0.13", "2.10"),
        "org.scalaz" % "scalaz-core_2.10" % "7.0.2",
        "org.scalaz" % "scalaz-effect_2.10" % "7.0.2",
        "com.typesafe.play" % "play-java_2.11" % Dependencies.playVersion,
        "com.typesafe.play" % "play-java-jdbc_2.11" % Dependencies.playVersion,
        "com.typesafe.play" % "play-java-ebean_2.11" % Dependencies.playVersion,
        "com.typesafe.play" % "play-java-ws_2.11" % Dependencies.playVersion,
        "com.typesafe.play" % "play-cache_2.11" % Dependencies.playVersion,
        "com.typesafe.play" % "play-docs_2.11" % Dependencies.playVersion,
        "com.typesafe.play" % "anorm_2.11" % Dependencies.playVersion,
        "com.typesafe.play" % "play-ws_2.11" % Dependencies.playVersion,

        "org.webjars" % "bootstrap" % "2.3.1",
        "org.webjars" % "flot" % "0.8.0",
        "org.webjars" % "bootstrap" % "3.0.0",
        "org.webjars" % "knockout" % "2.3.0",
        "org.webjars" % "requirejs" % "2.1.11-1",
        "org.webjars" % "leaflet" % "0.7.2",
        "org.webjars" % "squirejs" % "0.1.0",

        "com.typesafe.play.extras" % "play-geojson_2.11" % "1.1.0",
        "com.typesafe.akka" % "akka-contrib_2.11" % Dependencies.akkaVersion,
        "org.codehaus.plexus" % "plexus-interactivity-api" % "1.0-alpha-6",
        "org.codehaus.plexus" % "plexus-component-api" % "1.0-alpha-16",
        "org.jcraft" % "jsch" % "0.1.38"
        ),
      Keys.mappings in S3.upload <<= (Keys.packageBin in Universal, Packaging.minimalDist, Keys.version) map { (zip, minimalZip, v) =>
        Seq(minimalZip -> ("typesafe-activator/%s/typesafe-activator-%s-minimal.zip" format (v, v)),
            zip -> ("typesafe-activator/%s/typesafe-activator-%s.zip" format (v, v)))
      },
      S3.host in S3.upload := "downloads.typesafe.com.s3.amazonaws.com",
      S3.progress in S3.upload := true,
      S3.upload := {
        val log = Keys.streams.value.log
        val hash = (LocalTemplateRepo.checkTemplateCacheHash in TheActivatorBuild.localTemplateRepo).value
        log.info("Publishing to S3 with template index " + hash)
        S3.upload.value
      },
      logDownloadUrls := {
        val log = Keys.streams.value.log
        val version = Keys.version.value
        log.info(s"Download: http://downloads.typesafe.com/typesafe-activator/${version}/typesafe-activator-${version}.zip")
        log.info(s"Minimal:  http://downloads.typesafe.com/typesafe-activator/${version}/typesafe-activator-${version}-minimal.zip")
      }
    )
  )
}
