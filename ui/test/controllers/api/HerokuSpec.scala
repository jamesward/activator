package controllers.api

import java.io.File

import org.scalatestplus.play._
import play.api.libs.json.Json
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test._
import sbt.IO

class HerokuSpec extends PlaySpec with Results with OneAppPerSuite {

  class TestController() extends Controller with Heroku

  "login with no credentials" must {
    "fail" in {
      val json = Json.obj()
      val controller = new TestController()
      val result = controller.login("whatevs")(FakeRequest().withBody(json))
      status(result) mustBe BAD_REQUEST
    }
  }

  "login with invalid credentials" must {
    "fail" in {
      val json = Json.obj(
        "username" -> "foo@foo.com",
        "password" -> "bar")
      val controller = new TestController()
      val result = controller.login("whatevs")(FakeRequest().withBody(json))
      status(result) mustBe UNAUTHORIZED
    }
  }

  def withLogin(appDir: File)(testCode: Option[String] => Any) = {
    assume(sys.env.get("HEROKU_USERNAME").isDefined)
    assume(sys.env.get("HEROKU_PASSWORD").isDefined)

    val maybeAuthKey = for {
      username <- sys.env.get("HEROKU_USERNAME")
      password <- sys.env.get("HEROKU_PASSWORD")

      json = Json.obj(
        "username" -> username,
        "password" -> password)

      result = new TestController().login(appDir.getAbsolutePath)(FakeRequest().withBody(json))

      appApiKey <- session(result).get(Heroku.SESSION_APIKEY(appDir.getAbsolutePath))
      apiKey <- session(result).get(Heroku.SESSION_APIKEY())
    } yield {
      appApiKey must equal(apiKey)
      apiKey
    }

    maybeAuthKey must be('defined)

    testCode(maybeAuthKey)
  }

  def fakeRequest(appDir: File, apiKey: String) = {
    FakeRequest().withSession(Heroku.SESSION_APIKEY(appDir.getAbsolutePath) -> apiKey)
  }

  def withApp(testCode: Option[(String, HerokuAPI.App, File)] => Any) = {
    val appDir = new File(sys.props("java.io.tmpdir"), System.nanoTime().toString)
    withLogin(appDir) { maybeApiKey =>
      val maybeApiKeyAndApp = maybeApiKey.map { apiKey =>

        val result = new TestController().createApp(appDir.getAbsolutePath)(fakeRequest(appDir, apiKey))

        status(result) must equal(CREATED)

        val herokuApp = contentAsJson(result).as[HerokuAPI.App]

        (apiKey, herokuApp, appDir)
      }
      try {
        testCode(maybeApiKeyAndApp)
      } finally {
        maybeApiKeyAndApp.map {
          case (apiKey, herokuApp, tmpAppDir) =>
            HerokuAPI.destroyApp(apiKey, herokuApp.name)
            tmpAppDir.delete()
        }
      }
    }
  }

  "login with valid credentials" must {
    "add the auth key to the session" in withLogin(new File("foo")) { maybeApiKey =>
      maybeApiKey must be('defined)
    }
  }

  "createApp" must {
    "create an app on Heroku" in withApp { maybeAuthAndApp =>
      maybeAuthAndApp must be('defined)
    }
  }

  "getApps" must {
    "fail with an invalid apikey" in withApp { maybeAuthAndApp =>
      maybeAuthAndApp.map {
        case (apiKey, herokuApp, appDir) =>
          val result = new TestController().getApps(appDir.getAbsolutePath)(FakeRequest())
          status(result) must equal(UNAUTHORIZED)
      }
    }
    "work with a general apikey" in withApp { maybeAuthAndApp =>
      maybeAuthAndApp.map {
        case (apiKey, herokuApp, appDir) =>
          val result = new TestController().getApps(appDir.getAbsolutePath)(FakeRequest().withSession(Heroku.SESSION_APIKEY() -> apiKey))
          status(result) must equal(OK)
      }
    }
    "work with an app specific apikey" in withApp { maybeAuthAndApp =>
      maybeAuthAndApp.map {
        case (apiKey, herokuApp, appDir) =>
          val result = new TestController().getApps(appDir.getAbsolutePath)(FakeRequest().withSession(Heroku.SESSION_APIKEY(appDir.getAbsolutePath) -> apiKey))
          status(result) must equal(OK)
      }
    }
    "get the app that was created" in withApp { maybeAuthAndApp =>
      maybeAuthAndApp.map {
        case (apiKey, herokuApp, appDir) =>
          val result = new TestController().getApps(appDir.getAbsolutePath)(fakeRequest(appDir, apiKey))
          status(result) must equal(OK)
          val allHerokuApps = contentAsJson(result).as[Seq[HerokuAPI.App]]
          allHerokuApps.size must be > 0
          val newHerokuApp = allHerokuApps.filter(_.name == herokuApp.name)
          newHerokuApp.size must equal(1)
      }
    }
  }

  // cleanup the app
  "logs" must {
    "get the log stream" in withApp { maybeAuthAndApp =>
      // Wait here because:
      // Logplex was just enabled for this app. Please try fetching logs again in a few seconds.
      Thread.sleep(5000)

      maybeAuthAndApp.map {
        case (apiKey, herokuApp, appDir) =>
          val result = new TestController().logs(appDir.getAbsolutePath, herokuApp.name)(fakeRequest(appDir, apiKey))
          status(result) must equal(OK)
        // todo: test the chunked content somehow
      }
    }
  }

  "deploy" must {
    "deploy the app on Heroku" in withApp { maybeAuthAndApp =>
      maybeAuthAndApp.map {
        case (apiKey, herokuApp, appDir) =>

          val sampleApp = new File(appDir, "index.php")
          IO.write(sampleApp, "hello, world")

          val result = new TestController().deploy(appDir.getAbsolutePath, herokuApp.name)(fakeRequest(appDir, apiKey))
          status(result) must equal(OK)

          // todo: test the chunked build output somehow

          // Wait here because the build & release will take a little bit
          Thread.sleep(30000)

          val deployedResult = await(WS.url(herokuApp.web_url).get())

          deployedResult.status must equal(OK)
          deployedResult.body must equal("hello, world")
      }
    }
  }

}
