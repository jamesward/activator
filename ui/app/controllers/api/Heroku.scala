/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package controllers.api

import java.io.{ BufferedOutputStream, File, FileInputStream, FileOutputStream }
import java.util.zip.GZIPOutputStream

import org.apache.commons.compress.archivers.tar.{ TarArchiveEntry, TarArchiveOutputStream }
import org.apache.commons.compress.utils.IOUtils
import play.api.Play.current
import play.api.http.Status
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.{ JsObject, JsValue, Json }
import play.api.libs.ws._
import play.api.mvc.Security.{ AuthenticatedBuilder, AuthenticatedRequest }
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

// todo: csrf

trait Heroku {
  this: Controller =>

  def SESSION_APIKEY(): String = "HEROKU_API_KEY"
  def SESSION_APIKEY(location: String): String = location + "-APIKEY"
  def SESSION_APP(location: String): String = location + "-APP"

  private def jsonError(message: String): JsObject = Json.obj("error" -> message)
  private def jsonError(error: Exception): JsObject = jsonError(error.getMessage)

  val standardError: PartialFunction[Throwable, Result] = {
    case e: Exception => InternalServerError(jsonError(e))
  }

  def login(location: String) = Action.async(parse.json) { implicit request =>

    val maybeApiKeyFuture = for {
      username <- (request.body \ "username").asOpt[String]
      password <- (request.body \ "password").asOpt[String]
    } yield {
      HerokuAPI.getApiKey(username, password)
    }

    maybeApiKeyFuture.fold(Future.successful(BadRequest(jsonError("username and/or password not specified")))) { apiKeyFuture =>
      apiKeyFuture.map { apiKey =>
        // todo: persist auth apiKey into ~/.netrc so that it can also be used with the Heroku CLI??
        // put the key in a location specific and a general cookie so that apps can be associated with different authenticated users
        Ok.addingToSession(SESSION_APIKEY(location) -> apiKey, SESSION_APIKEY() -> apiKey)
      } recover {
        case e: Exception =>
          Unauthorized(jsonError(e))
      }
    }
  }

  def logout(location: String) = Action { implicit request =>
    Ok.removingFromSession(SESSION_APIKEY(location), SESSION_APIKEY())
  }

  def getDefaultApp(location: String) = Authenticated(location) { request =>
    request.session.get(SESSION_APP(location)).fold(NotFound(Results.EmptyContent()))(app => Ok(Json.obj("app" -> app)))
  }

  def setDefaultApp(location: String, app: String) = Authenticated(location) { implicit request =>
    Ok.addingToSession(SESSION_APP(location) -> app)
  }

  def getApps(location: String) = Authenticated(location).async { request =>
    HerokuAPI.getApps(request.apiKey).map { apps =>
      implicit val appFormat = HerokuAPI.appFormat
      Ok(Json.toJson(apps))
    } recover standardError
  }

  def createApp(location: String) = Authenticated(location).async { implicit request =>
    HerokuAPI.createApp(request.apiKey).map { app =>
      Created(Json.toJson(app)(HerokuAPI.appFormat)).addingToSession(SESSION_APP(location) -> app.name)
    } recover standardError
  }

  // todo: when build is complete, close the stream
  def deploy(location: String, app: String) = Authenticated(location).async { request =>

    val createSlugFuture = HerokuAPI.createSlug(request.apiKey, app, new File(location))

    createSlugFuture.flatMap { url =>
      HerokuAPI.buildSlug(request.apiKey, app, url).map {
        case (headers, enumerator) =>
          Ok.chunked(enumerator)
      } recover standardError
    } recover standardError
  }

  def logs(location: String, app: String) = Authenticated(location).async { request =>
    HerokuAPI.logs(request.apiKey, app).map {
      case (headers, enumerator) =>
        Ok.chunked(enumerator)
    } recover standardError
  }

  def getConfigVars(location: String, app: String) = Authenticated(location).async { request =>
    HerokuAPI.getConfigVars(request.apiKey, app).map(Ok(_))
  }

  def setConfigVars(location: String, app: String) = Authenticated(location).async(parse.json) { request =>
    HerokuAPI.setConfigVars(request.apiKey, app, request.body).map(Ok(_)).recover(standardError)
  }

  class HerokuRequest[A](val apiKey: String, request: Request[A]) extends WrappedRequest[A](request)

  def Authenticated(location: String) = new ActionBuilder[HerokuRequest] {
    def invokeBlock[A](request: Request[A], block: (HerokuRequest[A]) => Future[Result]) = {

      // first try to get the apikey for this location
      // fallback to the general apikey
      def userinfo(request: RequestHeader): Option[String] =
        request.session.get(SESSION_APIKEY(location)).orElse(request.session.get(SESSION_APIKEY()))

      def unauthorized(request: RequestHeader): Result = Unauthorized(jsonError("Not authenticated"))

      def authenticatedRequest(authRequest: AuthenticatedRequest[A, String]): Future[Result] =
        block(new HerokuRequest[A](authRequest.user, request))

      AuthenticatedBuilder(userinfo, unauthorized).authenticate(request, authenticatedRequest)
    }
  }

}

object Heroku extends Controller with Heroku

object HerokuAPI {

  var BASE_URL = "https://api.heroku.com/%s"

  // this uses an older API version by default because the login API we are using is only in the old API
  def ws(path: String): WSRequestHolder = WS.url(BASE_URL.format(path))

  // this one uses version 3
  def ws(path: String, apiKey: String, version: String = "3"): WSRequestHolder =
    ws(path).withAuth("", apiKey, WSAuthScheme.BASIC).withHeaders("Accept" -> s"application/vnd.heroku+json; version=$version")

  def getError(response: WSResponse): String =
    (response.json \ "error").asOpt[String].orElse((response.json \ "message").asOpt[String]).getOrElse("Unknown Error")

  def handleAsync[A](status: Int, block: JsValue => Future[A])(response: WSResponse): Future[A] = {
    response.status match {
      case s: Int if s == status =>
        block(response.json)
      case _ =>
        // usually an error of some sort
        Future.failed(new RuntimeException(getError(response)))
    }
  }

  def handle[A](status: Int, block: JsValue => A)(response: WSResponse): Future[A] = {
    response.status match {
      case s: Int if s == status =>
        Future.successful(block(response.json))
      case _ =>
        // usually an error of some sort
        Future.failed(new RuntimeException(getError(response)))
    }
  }

  def getApiKey(username: String, password: String): Future[String] = {
    ws("account").withAuth(username, password, WSAuthScheme.BASIC).get().flatMap(handle(Status.OK, _.\("api_key").as[String]))
  }

  def createApp(apiKey: String): Future[App] = {
    ws("apps", apiKey).post(Results.EmptyContent()).flatMap(handle(Status.CREATED, _.as[App]))
  }

  def destroyApp(apiKey: String, appName: String): Future[_] = {
    ws(s"apps/$appName", apiKey).delete()
  }

  def getApps(apiKey: String): Future[Seq[App]] = {
    ws("apps", apiKey).get().flatMap(handle(Status.OK, _.as[Seq[App]]))
  }

  def logs(apiKey: String, appName: String): Future[(WSResponseHeaders, Enumerator[Array[Byte]])] = {

    val requestJson = Json.obj("tail" -> true)

    ws(s"apps/$appName/log-sessions", apiKey).post(requestJson).flatMap(handleAsync(Status.CREATED, { response =>
      val url = (response \ "logplex_url").as[String]
      WS.url(url).stream()
    }))
  }

  def createSlug(apiKey: String, appName: String, appDir: File): Future[String] = {
    val requestJson = Json.obj("process_types" -> Json.obj())
    ws(s"/apps/$appName/slugs", apiKey).post(requestJson).flatMap(handleAsync(Status.CREATED, { response =>

      val id = (response \ "id").as[String]

      val url = (response \ "blob" \ "url").as[String]

      val tgzFile = new File(sys.props("java.io.tmpdir"), System.nanoTime().toString + ".tar.gz")

      // create the tgz
      val tgzos = new TarArchiveOutputStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(tgzFile))))

      // start with the files, not the dir
      appDir.listFiles.foreach { file =>
        addToTar(tgzos, file.getAbsolutePath, "")
      }

      tgzos.finish()

      tgzos.close()

      // put the tgz
      WS.url(url).put(tgzFile).flatMap { _ =>
        tgzFile.delete()

        // get the url to the slug
        ws(s"apps/$appName/slugs/$id", apiKey).get().flatMap(handle(Status.OK, _.\("blob").\("url").as[String]))
      }
    }))
  }

  // side effecting!!!
  def addToTar(tOut: TarArchiveOutputStream, path: String, base: String): Unit = {
    // manual exclude of target dirs
    if (!base.endsWith("target/") && !path.endsWith("target")) {
      val f = new File(path)
      val entryName = base + f.getName
      val tarEntry = new TarArchiveEntry(f, entryName)
      tOut.putArchiveEntry(tarEntry)

      if (f.isFile) {
        IOUtils.copy(new FileInputStream(f), tOut)
        tOut.closeArchiveEntry()
      } else {
        tOut.closeArchiveEntry()
        f.listFiles.foreach { child =>
          addToTar(tOut, child.getAbsolutePath, entryName + "/")
        }
      }
    }
  }

  def buildSlug(apiKey: String, appName: String, url: String): Future[(WSResponseHeaders, Enumerator[Array[Byte]])] = {
    val requestJson = Json.obj("source_blob" -> Json.obj("url" -> url))

    // set the api version to 'edge' in order to get the output_stream_url
    ws(s"apps/$appName/builds", apiKey, "edge").post(requestJson).flatMap(handleAsync(Status.CREATED, { json =>
      val url = (json \ "output_stream_url").as[String]
      WS.url(url).stream()
    }))
  }

  def getConfigVars(apiKey: String, appName: String): Future[JsValue] = {
    ws(s"apps/$appName/config-vars", apiKey).get().flatMap(handle(Status.OK, identity))
  }

  def setConfigVars(apiKey: String, appName: String, configVars: JsValue): Future[JsValue] = {
    ws(s"apps/$appName/config-vars", apiKey).patch(configVars).flatMap(handle(Status.OK, identity))
  }

  case class App(name: String, web_url: String)

  implicit val appFormat = Json.format[App]

}
