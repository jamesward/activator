/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package controllers.api

import java.io.{ OutputStreamWriter, OutputStream, ByteArrayOutputStream, File }
import java.net.URL

import com.heroku.api.HerokuAPI
import com.heroku.api.request.key.KeyAdd
import com.heroku.api.request.login.BasicAuthLogin
import com.jcraft.jsch.{ KeyPair, JSch }
import org.apache.http.HttpClientConnection
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{ TextProgressMonitor, ProgressMonitor, RepositoryBuilder }
import org.eclipse.jgit.transport.{ SshSessionFactory, URIish }
import play.api.Play
import play.api.libs.iteratee.{ Concurrent, Enumerator }
import play.api.libs.json.Json
import play.api.mvc.Security.{ AuthenticatedRequest, AuthenticatedBuilder }
import play.api.mvc._
import sbt.IO
import snap.AppManager

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

// todo: csrf

trait Heroku {
  this: Controller =>

  private def createSshKey(authKey: String) = {

    val herokuApi = new HerokuAPI(authKey)

    val SSH_KEY_COMMENT = "Typesafe-Activator-Key-" + authKey

    val sshDir = new File(sys.props("user.home"), ".ssh")
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

      herokuApi.addKey(sshPublicKey)
    }
  }

  def login = Action(parse.json) { request =>

    val maybeKey = for {
      username <- (request.body \ "username").asOpt[String]
      password <- (request.body \ "password").asOpt[String]
    } yield Try(HerokuAPI.obtainApiKey(username, password))
    // todo: persist auth token into ~/.netrc so that it can also be used with the Heroku CLI??

    maybeKey.fold(BadRequest("username and/or password not specified")) {
      case Success(key) =>
        createSshKey(key)
        Ok.withSession("herokuAuthKey" -> key)
      case Failure(error) =>
        Unauthorized(error.getMessage)
    }
  }

  def getApps(location: String) = Authenticated { request =>

    // check for a git repo with heroku remote(s)
    val repository = new RepositoryBuilder().setGitDir(new File(location, ".git")).build()

    if (!repository.getObjectDatabase.exists()) {
      repository.create()
    }

    val remotes = repository.getConfig.getSubsections("remote").asScala.map { remoteName =>
      new URIish(repository.getConfig.getString("remote", remoteName, "url"))
    }

    val maybeHerokuAppNames = remotes.filter(_.getHost == "heroku.com").map(_.getPath.stripSuffix(".git"))

    // verify the user has access to the app(s)
    val existingApps = maybeHerokuAppNames.map(request.api.getApp)

    Ok(play.libs.Json.toJson(existingApps.asJava).toString)
  }

  def createApp(location: String) = Authenticated { request =>
    val app = request.api.createApp

    val repository = new RepositoryBuilder().setGitDir(new File(location, ".git")).build()

    if (!repository.getObjectDatabase.exists()) {
      repository.create()
    }

    val config = repository.getConfig
    config.setString("remote", "heroku", "url", app.getGitUrl)
    config.save()

    Created(play.libs.Json.toJson(app).toString)
  }

  def deploy(location: String, app: String) = Authenticated { request =>

    val repository = new RepositoryBuilder().setGitDir(new File(location, ".git")).build()

    if (!repository.getObjectDatabase.exists()) {
      repository.create()
    }

    val gitRepo = new Git(repository)

    gitRepo.add().addFilepattern(".").call()

    gitRepo.commit().setMessage("Automatic commit for Heroku deployment").call()

    val e = Enumerator.outputStream { out =>
      val pm = new TextProgressMonitor(new OutputStreamWriter(out))
      val result = gitRepo.push().setRemote("heroku").setOutputStream(out).setProgressMonitor(pm).call
      result.asScala.foreach(r => out.write(r.getMessages.getBytes))
      out.close()
    }

    Ok.chunked(e)
  }

  def logs(app: String) = Authenticated { request =>
    val logStream = request.api.getLogs(app)

    val dataContent = Enumerator.fromStream(logStream.openStream())

    Ok.chunked(dataContent)
  }

  class HerokuRequest[A](val api: HerokuAPI, request: Request[A]) extends WrappedRequest[A](request)

  object Authenticated extends ActionBuilder[HerokuRequest] {
    def invokeBlock[A](request: Request[A], block: (HerokuRequest[A]) => Future[Result]) = {
      SshSessionFactory.setInstance(new HerokuSshSessionFactory)

      val authBuilder = AuthenticatedBuilder { request =>
        request.session.get("herokuAuthKey").map(new HerokuAPI(_))
      }

      authBuilder.authenticate(request, { authRequest: AuthenticatedRequest[A, HerokuAPI] =>
        block(new HerokuRequest[A](authRequest.user, request))
      })
    }
  }

}

object Heroku extends Controller with Heroku

// sets up the ssh config for connecting to heroku
// mostly based on JschConfigSessionFactory
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import org.eclipse.jgit.internal.JGitText
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.transport._
import org.eclipse.jgit.util.FS

import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

class HerokuSshSessionFactory extends SshSessionFactory {

  override def getSession(uri: URIish, credentialsProvider: CredentialsProvider, fs: FS, tms: Int): RemoteSession = {
    val user = uri.getUser
    val host = uri.getHost

    try {
      val jsch = new JSch()

      jsch.setKnownHosts(getClass.getClassLoader.getResourceAsStream("known_hosts"))

      val sshDir = new File(sys.props("user.home"), ".ssh")

      jsch.addIdentity(new File(sshDir, "id_rsa-heroku-activator").getAbsolutePath)

      val session = jsch.getSession(user, host)

      if (!session.isConnected) {
        session.connect(tms)
      }

      new JschSession(session, uri)
    } catch {
      case je: JSchException =>
        je.getCause match {
          case e: UnknownHostException =>
            throw new TransportException(uri, JGitText.get().unknownHost)
          case e: ConnectException =>
            throw new TransportException(uri, e.getMessage)
          case _ =>
          // whatevs
        }
        throw new TransportException(uri, je.getMessage, je)
      case io: IOException =>
        throw new TransportException(uri, io.getMessage, io)
      case e: Exception =>
        throw new TransportException(uri, e.getMessage, e)
    }

  }

}
