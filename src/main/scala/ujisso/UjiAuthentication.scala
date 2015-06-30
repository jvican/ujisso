package ujisso

import java.util.concurrent.TimeUnit

import akka.event.LoggingAdapter
import akka.util.Timeout
import base64.Decode
import spray.http.{HttpCookie, StatusCodes, Uri}
import spray.routing._
import ujisso.UjiError._
import xmlrpc.Xmlrpc.XmlrpcServer
import xmlrpc.protocol.Deserializer.AnyErrors
import xmlrpc.protocol.XmlrpcProtocol._
import xmlrpc.{Xmlrpc, XmlrpcResponse}

import scala.language.postfixOps
import scalaz.Scalaz._
import scalaz._

/**
 * Uji Authentication protocol at this moment (2015-16). Mix in to use.
 */
trait UjiAuthentication extends UjiProtocol with UjiRejections with SprayRoutingWithXmlrpc with HttpService {
  val XmlrpcPath = "xmlrpc.uji.es/lsmSSO-83/"
  val XmlrpcServerUri: String = "http://" + XmlrpcPath + "server.php"
  implicit val UjiXmlrpcServer = XmlrpcServer(XmlrpcServerUri)

  // Stubs for xmlrpc invocations
  implicit val timeout: Timeout = Timeout(7, TimeUnit.SECONDS)
  implicit val log: LoggingAdapter

  implicit def executionContext = actorRefFactory.dispatcher

  val UjiLoginEndpoint = Uri("https://" + XmlrpcPath + "lsmanage.php")
  val UjiLogoutEndpoint = Uri("https://" + XmlrpcPath + "logout_sso.php")

  val TokenLabel = "UJITok"
  val SessionLabel = "LSMSession"
  val PrivateKeyLabel = SessionLabel + "PK"
  val IndexLabel = SessionLabel + "IDX"
  
  val GetPrivateKeyAndIndex = "lsm.get_pk_idx"
  val GetLoginSession = "lsm.get_login_session"

  // Only english (en), spanish (es) and catalan (ca) are supported
  private[ujisso] case class UserLanguage(code: String)

  val LanguageLabel = "uji-lang"
  val Catalan = UserLanguage("ca")
  val English = UserLanguage("en")
  val Spanish = UserLanguage("es")
  implicit val DefaultLanguage = Catalan

  /**
   * Default configuration settings.
   */
  val HttpOnlyCookies = true
  val SecureCookies = false
  val DefaultRetry = 3
  val RetryNewSession = DefaultRetry
  val RetryCheckSession = DefaultRetry

  def withCookieDefaults(cookie: HttpCookie) = cookie.copy(path = Some("/"), domain = Some(host), httpOnly = HttpOnlyCookies, secure = SecureCookies)

  lazy val identifier = callbackWhenLoggedIn.authority.host.address.replace(".uji.es", "").replace(".", "_")
  lazy val host = callbackWhenLoggedIn.authority.host.address

  lazy val SessionName = SessionLabel + identifier
  lazy val PrivateKeyName = PrivateKeyLabel + identifier
  lazy val IndexName = IndexLabel + identifier

  val routesUjiAuth =
    pathPrefix("uji") {
      handleRejections(UjiRejectionHandler.apply()) {
        path("login") {
          get {
            optionalCookie(SessionName) {
              case Some(cookie) => authenticated(cookie.content.trim)
              case None => parameters(TokenLabel.?) {
                case Some(token) if token.nonEmpty => nonAuthenticatedPhase2(token)
                case Some(token) => reject(EmptyToken)
                case None => nonAuthenticatedPhase1
              }
            }
          }
        } ~
          path("logout") {
            get {
              logout
            }
          }
      }
    }

  override def authenticated(rawToken: String): Route = {
    def runHookOrReject(conf: LoginConfirmation): Route = conf match {
      case confirmed if confirmed.isOk => hookWhenUserAuthenticated(confirmed.token, confirmed.authenticatedUsername)
      case unconfirmed => reject(EmptyLoginConfirmation)
    }

    def logAndRedirectToLogout(errors: AnyErrors): Route = {
      errors foreach (e => log.error(e.friendlyMessage))
      log.info("Logging out because the server has not reconfirmed the existing session for the token $rawToken")
      logout
    }

    val f = () => isStillLogged(rawToken)

    xmlrpcCallAndRoute(f, logAndRedirectToLogout, runHookOrReject, RetryCheckSession)
  }

  override def nonAuthenticatedPhase1: Route = {
    def setCookiesAndRedirect(session: UserSession) =
      setCookie(
        withCookieDefaults(HttpCookie(PrivateKeyName, session.privateKey)),
        withCookieDefaults(HttpCookie(IndexName, session.index))
      ) {
        redirect(buildUjiLoginUri(callbackWhenLoggedIn, session.index), StatusCodes.Found)
      }

    def logAndReject(errors: AnyErrors) = {
      logErrors(errors)
      reject(NewSessionFailure)
    }

    val call = () => openUserSession
    xmlrpcCallAndRoute(call, logAndReject, setCookiesAndRedirect, RetryNewSession)
  }

  override def nonAuthenticatedPhase2(token: String): Route =
    requestUri { uri =>
      cookie(PrivateKeyName) { cookie =>
        decryptUjiToken(token, cookie.content) match {
          case Success(rawToken) =>
            setCookie(
              withCookieDefaults(HttpCookie(SessionName, rawToken))
            ) {
              val safeUri = removeUjiTokFromUri(uri)
              redirect(safeUri, StatusCodes.Found)
            }

          case Failure(errors) =>
            logErrors(errors)
            reject(TokenDecryptionFailure)
        }
      }
    }

  override def logout: Route = {
    deleteCookie(PrivateKeyName) {
      deleteCookie(IndexName) {
        deleteCookie(SessionName) {
          redirect(buildUjiLogoutUri(callbackWhenLoggedOut), StatusCodes.Found)
        }
      }
    }
  }

  /**
   * User session opened with the Uji SSO
   *
   * @param privateKey Key to decrypt cookie (UJITok) when the user has been successfully authenticated
   * @param index Reference (index) to the private key used to encrypt the cookie. Useful because the
   *               server needs to know which private key has to use to encrypt the cookie
   */
  case class UserSession(privateKey: String, index: String)

  /**
   * We try to open a new session against the Uji Server (via XML-RPC) if the user has not
   * been authenticated. The server returns a private key and an index.
   */
  private[ujisso] def openUserSession: XmlrpcResponse[UserSession] =
    Xmlrpc.invokeMethod[Empty, Seq[String]](GetPrivateKeyAndIndex) map ( l =>
      UserSession(l.head, l.tail.head)
    )

  case class LoginConfirmation(authenticatedUsername: String, token: String) {
    lazy val isOk = authenticatedUsername.trim.nonEmpty && token.trim.nonEmpty
  }

  /**
   * Public method because the client of the library may used this method on his own to check
   * if a token is valid. This check can be done out of the scope, as a `ContextAuthenticator`.
   */
  def isStillLogged(token: String): XmlrpcResponse[LoginConfirmation] =
    Xmlrpc.invokeMethod[(String, String), Seq[String]](
      GetLoginSession,
      (token, identifier)
    ) map (l => LoginConfirmation(l.head, l.tail.head))

  /**
   * We get an encrypted token from the client. After we had retrieved the public key from
   * the server, so we use it to decrypt that token.
   *
   * The token is encoded with base64, so we decode it. Then, we decrypt (OTP) the result
   * by xor'ing with the private key. Both key and token have to have the same length.
   *
   * @param encodedToken The token to be decrypted
   * @param privateKey The key used to decrypt
   *
   * @return A result with a possible failure or a success
   */
  private[ujisso] def decryptUjiToken(encodedToken: String, privateKey: String): Validation[UjiErrors, String] = {

    def decodeBase64(encoded: String): Validation[Decode.Failure, String] =
      base64.Decode(encoded)
        .right.map(new String(_))
        .validation

    def decryptOneTimePad(rawToken: String): String =
      rawToken.toCharArray
        .zip(privateKey.toCharArray)
        .map { case (m1, k1) => (m1 ^ k1).toChar } mkString

    val token = for {
      encrypted <- decodeBase64(encodedToken)
      token <- decryptOneTimePad(encrypted).success[String]
      if encrypted.length == privateKey.length
    } yield token

    token match {
      case Success(correct: String) => correct.success[UjiErrors]
      case Failure(invalid: String) => DecryptionLengthMismatch.failureNel[String]
      case Failure(_) => InvalidToken(encodedToken).failureNel[String]
    }
  }

  /**
   * Building the redirect url to the Uji login endpoint.
   *
   * @param callback Uri we want the Uji Server to redirect after the authentication process.
   *                  It's not percent-encoded because it's not required following the the RFC3986.
   * @param index When a session is opened, we need the index of the private key to build the url
   */
  private[ujisso] def buildUjiLoginUri(callback: Uri, index: String)(implicit language: UserLanguage): Uri =
    UjiLoginEndpoint withQuery (
        "Url" -> callback.toString, "lang" -> language.code, "ident" -> identifier, "dimitri" -> index
    )

  /**
   * Building the redirect url to the Uji logout endpoint.
   *
   * @param callback Uri we want the Uji Server to redirect after the authentication process
   *                  It's not percent-encoded because it's not required following the the RFC3986.
   */
  private[ujisso] def buildUjiLogoutUri(callback: Uri)(implicit language: UserLanguage): Uri =
    UjiLogoutEndpoint withQuery (
        "Url" -> callback.toString, "ident" -> identifier, "lang" -> language.code
      )

  /**
   * This method removes the UJITok parameter the user sends to the server when has been
   * authenticated against the UJI Server.
   *
   * @param unsafe Uri sent by the user
   * @return Uri sent back to the client
   */
  private[ujisso] def removeUjiTokFromUri(unsafe: Uri): Uri = unsafe withQuery unsafe.query.filterNot(_._1 == TokenLabel)
}
