package com.distributedstuff.services.common.http

import java.io.{File, IOException}
import java.net
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

import com.distributedstuff.services.common.ExecutionContextExecutorServiceBridge
import com.distributedstuff.services.internal.LoadBalancedClient
import com.google.common.io.Files
import com.squareup.okhttp._
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}

private object ClientHolder {
  val client = new OkHttpClient()
  val empty = "".getBytes("UTF-8")
}

private trait Method
private object GET extends Method
private object PUT extends Method
private object POST extends Method
private object DELETE extends Method
private object PATCH extends Method
private object HEAD extends Method
private object OPTIONS extends Method

object Http {
  def url(u: => String)(implicit client: OkHttpClient = ClientHolder.client) = new RequestHolder(url = u, client = client)
  def empty()(implicit client: OkHttpClient = ClientHolder.client) = new RequestHolder(url = "", client = client)
}

case class Cookie(name: String, value: String, domain: Option[String] = None, expires:  Option[String] = None, path: Option[String] = None, secure: Boolean = false, httpOnly: Boolean = false) {
  def toCookie = s"$name=$value" + domain.map("; domain=" + _).getOrElse("") + expires.map("; expires=" + _).getOrElse("") + path.map("; path=" + _).getOrElse("") + (if(secure) "; secure" else "") + (if(httpOnly) "; HttpOnly" else "")
}

class RequestHolder(
                      url: => String,
                      client: OkHttpClient,
                      body: Option[Array[Byte]] = None,
                      rbody: Option[RequestBody] = None,
                      vhost: Option[String] = None,
                      headers: Seq[(String, String)] = Seq(),
                      params: Seq[(String, String)] = Seq(),
                      timeout: Option[Duration] = Some(Duration(60, TimeUnit.SECONDS)),
                      redirect: Boolean = false,
                      authenticator: Option[Authenticator] = None,
                      proxy: Option[java.net.Proxy] = None,
                      media: String = "application/octet-stream",
                      parts: Seq[String] = Seq(),
                      apiClient: Option[LoadBalancedClient] = None
                   ) {

  def copy(
           url: => String = this.url,
           client: OkHttpClient = this.client,
           body: Option[Array[Byte]] = this.body,
           rbody: Option[RequestBody] = this.rbody,
           vhost: Option[String] = this.vhost,
           headers: Seq[(String, String)] = this.headers,
           params: Seq[(String, String)] = this.params,
           timeout: Option[Duration] = this.timeout,
           redirect: Boolean = this.redirect,
           authenticator: Option[Authenticator] = this.authenticator,
           proxy: Option[java.net.Proxy] = this.proxy,
           media: String = this.media,
           parts: Seq[String] = this.parts,
           apiClient: Option[LoadBalancedClient] = this.apiClient
     ): RequestHolder = {
    new RequestHolder(url, client, body, rbody, vhost, headers, params, timeout, redirect, authenticator, proxy, media, parts, apiClient)
  }

  def addPart(part: String): RequestHolder = this.copy(parts = this.parts :+ part)

  def withPart(part: String): RequestHolder = addPart(part)

  def withUrl(u: => String): RequestHolder = this.copy(url = u)

  private[services] def withApiClient(cli: LoadBalancedClient): RequestHolder = this.copy(apiClient = Some(cli))

  def withAuth(a: Authenticator): RequestHolder = this.copy(authenticator = Some(a))

  def withAuth(user: String, password: String): RequestHolder = this.copy(authenticator = Some(new Authenticator {
    override def authenticateProxy(proxy: net.Proxy, response: Response): Request = null
    override def authenticate(proxy: net.Proxy, response: Response): Request = {
      response.request().newBuilder().header("Authorization", Credentials.basic(user, password)).build()
    }
  }))

  //def withProxy(p: java.net.Proxy): RequestHolder = this.copy(proxy = Some(p))

  def withFollowSslRedirects(follow: Boolean): RequestHolder = this.copy(redirect = follow)

  def withParams(p: (String, String)*): RequestHolder = this.copy(params = params ++ p.toSeq)

  def withRequestTimeout(timeout: Int): RequestHolder = this.copy(timeout = Some(Duration(timeout, TimeUnit.SECONDS)))

  def withRequestTimeout(timeout: String): RequestHolder = this.copy(timeout = Some(Duration(timeout)))

  def withRequestTimeout(timeout: Duration): RequestHolder = this.copy(timeout = Some(timeout))

  def withVirtualHost(vh: String): RequestHolder = this.copy(vhost = Some(vh))

  def withMediaType(mt: String): RequestHolder = this.copy(media = mt)

  def withBody(body: String): RequestHolder = this.copy(body = Some(body.getBytes("UTF-8")), media = "text/plain")

  def withBody(jsv: JsValue): RequestHolder = this.copy(body = Some(Json.stringify(jsv).getBytes("UTF-8")), media = "application/json")

  def withBody(body: Array[Byte]): RequestHolder = this.copy(body = Some(body), media = "application/octet-stream")

  def withBody(file: File): RequestHolder = this.copy(body = Some(Files.toByteArray(file)), media = "application/octet-stream" )

  def withBody(b: RequestBody): RequestHolder = this.copy(rbody = Some(b))

  def withHeaders(h: (String, String)*): RequestHolder = this.copy(headers = headers ++ h.toSeq)

  def withCookies(cookies: Cookie*): RequestHolder = withHeaders(cookies.toSeq.map(c => ("Set-Cookie", c.toCookie)):_*)

  def withExecutionContext(implicit ec: ExecutionContext) = this.copy(client = client.setDispatcher(new Dispatcher(ExecutionContextExecutorServiceBridge(ec))))

  def withEC(implicit ec: ExecutionContext) = this.copy(client = client.setDispatcher(new Dispatcher(ExecutionContextExecutorServiceBridge(ec))))

  def get() = execute(buildClient(), buildRequest(GET))

  def patch() = execute(buildClient(), buildRequest(PATCH))

  def post() = execute(buildClient(), buildRequest(POST))

  def put() = execute(buildClient(), buildRequest(PUT))

  def delete() = execute(buildClient(), buildRequest(DELETE))

  def head() = execute(buildClient(), buildRequest(HEAD))

  def options() = execute(buildClient(), buildRequest(OPTIONS))

  private[this] def buildClient(): OkHttpClient = {
    var c = client.clone()
    c = c.setFollowSslRedirects(redirect)
    if (proxy.isDefined) c = c.setProxy(proxy.get)
    if (authenticator.isDefined) c = c.setAuthenticator(authenticator.get)
    if (timeout.isDefined) {
      c.setConnectTimeout(timeout.get.toMillis, TimeUnit.MILLISECONDS)
      c.setReadTimeout(timeout.get.toMillis, TimeUnit.MILLISECONDS)
      c.setWriteTimeout(timeout.get.toMillis, TimeUnit.MILLISECONDS)
    }
    c
  }

  private[this] def buildRequest(method: Method): Request = {
    var builder = new Request.Builder()
    for(header <- headers) {
      builder = builder.addHeader(header._1, header._2)
    }
    val theUrl = apiClient.flatMap(_.bestService).map(_.url).getOrElse(url)
    val urlWithPart = theUrl + parts.mkString("/").replace("//", "/")
    val finalUrl = params match {
      case p if p.nonEmpty && urlWithPart.contains("?") => urlWithPart + "&" + params.toSeq.map(t => (t._1, URLEncoder.encode(t._2, "UTF-8"))).mkString("&")
      case p if p.nonEmpty => urlWithPart + "?" + params.toSeq.map(t => (t._1, URLEncoder.encode(t._2, "UTF-8"))).mkString("&")
      case p => urlWithPart
    }
    builder = builder.url(finalUrl)
    val rb: RequestBody =  rbody.getOrElse(RequestBody.create(MediaType.parse(media), body.getOrElse(ClientHolder.empty)))
    method match {
      case GET => builder = builder.get()
      case PUT => builder = builder.put(rb)
      case POST => builder = builder.post(rb)
      case DELETE => builder = builder.delete()
      case PATCH => builder = builder.patch(rb)
      case HEAD => builder = builder.head()
      case OPTIONS => builder = builder.method("OPTIONS", rb)
    }
    builder.build()
  }

  private[this] def execute(client: OkHttpClient, request: Request): Future[Response] = {
    val p = Promise[Response]()
    client.newCall(request).enqueue(new Callback {
      override def onFailure(request: Request, e: IOException): Unit = p.tryFailure(e)
      override def onResponse(response: Response): Unit = p.trySuccess(response)
    })
    p.future
  }
}

package object support {
  implicit final class JsonSupport(response: Response) {
    def json: JsValue = Json.parse(response.body().string())
    def jsonOpt: Option[JsValue] = if (response.isSuccessful) Some(json) else None
  }
  implicit final class FutureJsonSupport(response: Future[Response]) {
    def json(implicit ec: ExecutionContext): Future[JsValue] = response.map(r => Json.parse(r.body().string()))
    def jsonOpt(implicit ec: ExecutionContext): Future[Option[JsValue]] = response.map {
      case r if r.isSuccessful => Some( Json.parse(r.body().string()))
      case _ => None
    }
  }
}
