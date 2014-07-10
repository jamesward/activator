/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package controllers.api

import java.io.{ ByteArrayOutputStream, File }
import java.net.URL

import com.heroku.api.HerokuAPI
import com.heroku.api.request.key.KeyAdd
import com.heroku.api.request.login.BasicAuthLogin
import com.jcraft.jsch.{ KeyPair, JSch }
import org.apache.http.HttpClientConnection
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryBuilder
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.Json
import play.api.mvc.Security.{ AuthenticatedRequest, AuthenticatedBuilder }
import play.api.mvc._
import snap.AppManager

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Heroku extends Controller {

  private def createSshKey(herokuApi: HerokuAPI) = {

    val SSH_KEY_COMMENT = "Key for Typesafe Activator to connect to Heroku"

    val sshDir = new File(System.getProperty("user.home"), ".ssh")
    sshDir.mkdirs()

    val privateKeyFile = new File(sshDir, "id_rsa-heroku-activator")

    if (!privateKeyFile.exists()) {
      val jsch = new JSch()
      val keyPair = KeyPair.genKeyPair(jsch, KeyPair.RSA)
      keyPair.writePrivateKey(privateKeyFile.getAbsolutePath)

      keyPair.writePublicKey(new File(sshDir, "id_rsa-heroku-activator.pub").getAbsolutePath, SSH_KEY_COMMENT)

      val publicKeyOutputStream = new ByteArrayOutputStream()
      keyPair.writePublicKey(publicKeyOutputStream, SSH_KEY_COMMENT)
      publicKeyOutputStream.close()

      val sshPublicKey = new String(publicKeyOutputStream.toByteArray)

      /*
      val knownHostsFile = new File(getClass().getClassLoader().getResource("known_hosts").getFile())
      FileUtils.copyFileToDirectory(knownHostsFile, fakeUserHomeSshDir)
      */

      herokuApi.addKey(sshPublicKey)
    }
  }

  def login = Action(parse.json) { request =>

    val maybeKey = for {
      username <- (request.body \ "username").asOpt[String]
      password <- (request.body \ "password").asOpt[String]
    } yield HerokuAPI.obtainApiKey(username, password)

    // todo: persist auth token into ~/.netrc so that it can also be used with the Heroku CLI??

    maybeKey.fold(BadRequest("username and/or password not specified")) { key =>
      createSshKey(new HerokuAPI(key))
      Ok.withSession("herokuAuthKey" -> key)
    }
  }

  def getApps(location: String) = Authenticated { request =>

    // check for a git repo with heroku remote(s)
    val repository = new RepositoryBuilder().setGitDir(new File(location, ".git")).build()

    if (repository.getConfig == null) {
      repository.create()
    }

    val remotes = repository.getConfig.getSubsections("remote").asScala.map { remoteName =>
      new URL(repository.getConfig.getString("remote", remoteName, "url"))
    }

    val maybeHerokuAppNames = remotes.filter(_.getHost == "heroku.com").map(_.getFile.stripSuffix(".git"))

    // verify the user has access to the app(s)
    val existingApps = maybeHerokuAppNames.map(request.api.getApp)

    Ok(play.libs.Json.toJson(existingApps).toString)
  }

  def createApp(location: String) = Authenticated { request =>
    val app = request.api.createApp

    val repository = new RepositoryBuilder().setGitDir(new File(location, ".git")).build()

    repository.getConfig.setString("remote", "heroku", "url", app.getGitUrl)

    Ok(play.libs.Json.toJson(app).toString)
  }

  def deploy(location: String, app: String) = Authenticated { request =>

    val repository = new RepositoryBuilder().setGitDir(new File(location, ".git")).build()

    val gitRepo = new Git(repository)

    gitRepo.commit().setMessage("Automatic commit for Heroku deployment").call()

    gitRepo.push().setRemote("herkoku").call()

    NotImplemented
  }

  def logs(app: String) = Authenticated { request =>
    val logStream = request.api.getLogs(app)

    val dataContent = Enumerator.fromStream(logStream.openStream())

    Ok.chunked(dataContent)
  }

  class HerokuRequest[A](val api: HerokuAPI, request: Request[A]) extends WrappedRequest[A](request)

  object Authenticated extends ActionBuilder[HerokuRequest] {
    def invokeBlock[A](request: Request[A], block: (HerokuRequest[A]) => Future[Result]) = {
      val authBuilder = AuthenticatedBuilder { request =>
        request.session.get("herokuAuthKey").map(new HerokuAPI(_))
      }

      authBuilder.authenticate(request, { authRequest: AuthenticatedRequest[A, HerokuAPI] =>
        block(new HerokuRequest[A](authRequest.user, request))
      })
    }
  }

}
