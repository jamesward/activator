package controllers.api

import java.io.File
import java.util.concurrent.TimeUnit

import akka.util.Timeout
import com.heroku.api.HerokuAPI
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.transport.URIish
import org.junit.runner.RunWith
import org.specs2.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.{ Fragments, Step }
import play.api.libs.json.{ JsObject, Json }
import play.api.libs.ws.WS
import play.api.mvc.{ Controller, Results }
import play.api.test.{ FakeRequest, PlaySpecification, WithApplication }
import sbt.IO

import scala.collection.JavaConverters._

// todo: look into steps in spec2 as a better way to control test flow

@RunWith(classOf[JUnitRunner])
class HerokuTest extends PlaySpecification with Results {

  val homeDir = new File(sys.props("java.io.tmpdir"), System.nanoTime().toString)
  val appDir = new File(homeDir, "app")

  lazy val maybeAuthKey: Option[String] = {
    for {
      username <- sys.env.get("HEROKU_USERNAME")
      password <- sys.env.get("HEROKU_PASSWORD")

      json = Json.obj(
        "username" -> username,
        "password" -> password)

      result = new TestController().login()(FakeRequest().withBody(json))

      authKey <- session(result).get("herokuAuthKey")
    } yield authKey
  }

  // from: http://stackoverflow.com/questions/16936811/execute-code-before-and-after-specification
  // see http://bit.ly/11I9kFM (specs2 User Guide)
  override def map(fragments: => Fragments) =
    Step(beforeAll()) ^ fragments ^ Step(afterAll())

  def beforeAll() = {
    appDir.mkdirs()

    // set the user.home so that
    sys.props.update("user.home", homeDir.getAbsolutePath)
  }

  def afterAll() = {

    // cleanup stuff on heroku
    maybeAuthKey.map { authKey =>
      val apps = getApps(authKey)

      val herokuApi = new HerokuAPI(authKey)

      herokuApi.removeKey("Typesafe-Activator-Key-" + authKey)

      apps.foreach(app => herokuApi.destroyApp(app.getName))
    }

    homeDir.delete()
  }

  def getApps(authKey: String): Set[com.heroku.api.App] = {

    val repository = new RepositoryBuilder().setGitDir(new File(appDir, ".git")).build()

    val remotes = repository.getConfig.getSubsections("remote").asScala.map { remoteName =>
      new URIish(repository.getConfig.getString("remote", remoteName, "url"))
    }

    val maybeHerokuAppNames = remotes.filter(_.getHost == "heroku.com").map(_.getPath.stripSuffix(".git"))

    maybeHerokuAppNames.map(new HerokuAPI(authKey).getApp).toSet
  }

  class TestController() extends Controller with Heroku

  "login with valid credentials" should {

    "create an ssh key" in new WithApplication {

      homeDir.exists must beTrue

      maybeAuthKey.fold("skipped" in skipped("heroku username and/or password invalid")) { authKey =>

        val sshDir = new File(homeDir, ".ssh")

        val privateKey = new File(sshDir, "id_rsa-heroku-activator")

        privateKey.exists must beTrue

        val publicKey = new File(sshDir, "id_rsa-heroku-activator.pub")

        publicKey.exists must beTrue

        val herokuApi = new HerokuAPI(authKey)

        herokuApi.listKeys().asScala.find(_.getContents == IO.read(publicKey)) must beSome
      }
    }
  }

  "login with no credentials" should {
    "fail" in {
      val json = Json.obj()
      val controller = new TestController()
      val result = controller.login()(FakeRequest().withBody(json))
      status(result) must be equalTo BAD_REQUEST
    }
  }

  "login with invalid credentials" should {
    "fail" in {
      val json = Json.obj(
        "username" -> "foo@foo.com",
        "password" -> "bar")
      val controller = new TestController()
      val result = controller.login()(FakeRequest().withBody(json))
      status(result) must be equalTo UNAUTHORIZED
    }
  }

  "createApp with valid token" should {
    "create a new app" in new WithApplication {
      maybeAuthKey.fold("skipped" in skipped("heroku username and/or password invalid")) { authKey =>

        val sampleApp = new File(appDir, "index.php")
        IO.write(sampleApp, "hello, world")

        val result = new TestController().createApp(appDir.getAbsolutePath)(FakeRequest().withSession("herokuAuthKey" -> authKey))

        val appName = (contentAsJson(result) \ "name").asOpt[String]

        appName must beSome

        status(result) must be equalTo CREATED

        val repository = new RepositoryBuilder().setGitDir(new File(appDir, ".git")).build()

        repository.getConfig.getString("remote", appName.get, "url") must be equalTo s"git@heroku.com:${appName.get}.git"
      }
    }
  }

  "getApps with valid token" should {
    "return the heroku apps for a project" in new WithApplication {
      maybeAuthKey.fold("skipped" in skipped("heroku username and/or password invalid")) { authKey =>

        val result = new TestController().getApps(appDir.getAbsolutePath)(FakeRequest().withSession("herokuAuthKey" -> authKey))

        status(result) must be equalTo OK

        contentAsJson(result).as[Seq[JsObject]].size must be greaterThan 0
      }
    }
  }

  "deploy with valid token" should {
    "deploy the app" in new WithApplication {
      maybeAuthKey.fold("skipped" in skipped("heroku username and/or password invalid")) { authKey =>

        val result = new TestController().deploy(appDir.getAbsolutePath, "whatevs")(FakeRequest().withSession("herokuAuthKey" -> authKey))

        contentAsString(result)(Timeout(1, TimeUnit.MINUTES)) must contain("deployed to Heroku")

        status(result) must be equalTo OK

        val apps = getApps(authKey)

        val deployedResult = await(WS.url(apps.head.getWebUrl).get())

        deployedResult.status must be equalTo OK
        deployedResult.body must be equalTo "hello, world"
      }
    }
  }

  "logs with valid token" should {
    "return some logs" in new WithApplication {
      maybeAuthKey.fold("skipped" in skipped("heroku username and/or password invalid")) { authKey =>
        val result = new TestController().logs(getApps(authKey).head.getName)(FakeRequest().withSession("herokuAuthKey" -> authKey))

        status(result) must be equalTo OK

        contentAsString(result).length must be greaterThan 0
      }
    }
  }

}
