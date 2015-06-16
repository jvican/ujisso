package api.uji

import java.util.concurrent.TimeUnit

import akka.event.LoggingAdapter
import akka.util.Timeout
import api.uji.UjiError.UjiErrors
import base64.Decode
import spray.http.{HttpCookie, StatusCodes, Uri}
import spray.routing._
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
trait UjiAuthentication extends UjiProtocol with HttpService {
  val XmlrpcPath = "xmlrpc.uji.es/lsmSSO-83/"
  val XmlrpcServerUri: String = "http://" + XmlrpcPath + "server.php"
  implicit val UjiXmlrpcServer = XmlrpcServer(XmlrpcServerUri)

  // Stubs for xmlrpc invocations
  implicit val timeout: Timeout = Timeout(5, TimeUnit.SECONDS)
  val log: LoggingAdapter

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
  private[uji] case class UserLanguage(code: String)

  val LanguageLabel = "uji-lang"
  val Catalan = UserLanguage("ca")
  val English = UserLanguage("en")
  val Spanish = UserLanguage("es")
  implicit val DefaultLanguage = Catalan

  val HttpOnlyCookies = false
  val SecureCookies = false

  lazy val identifier = callbackWhenLoggedIn.authority.host.address.replace(".uji.es", "").replace(".", "_")
  lazy val host = callbackWhenLoggedIn.authority.host.address

  lazy val SessionName = SessionLabel + identifier
  lazy val PrivateKeyName = PrivateKeyLabel + identifier
  lazy val IndexName = IndexLabel + identifier

  val routesUjiAuth =
    pathPrefix("uji") {
      path("login") {
        get {
          optionalCookie(SessionName) {
            case Some(cookie) => authenticated(cookie.content.trim)
            case None => parameters(TokenLabel.?) {
              case Some(token) if token.nonEmpty => nonAuthenticatedPhase2(token)
              case Some(token) => complete("Hey, you have tried to redirect without any UJITok, no luck boy!")
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

  sealed trait AuthenticationReport
  case class Ok(user: String) extends AuthenticationReport
  case class Failed(errors: Option[AnyErrors] = None) extends AuthenticationReport

  override def authenticated(rawToken: String): Route = {
    val confirmation = isStillLogged(rawToken) map (c => (c.isOk, c.authenticatedUsername))

    onSuccess(confirmation.underlying) {
      case Success(conf) => hookWhenUserAuthenticated(conf._2)

      case Failure(errors) =>
        errors foreach (e => log.error(e.friendlyMessage))
        log.info("Logging out because the server has not reconfirmed the existing session")
        logout
    }
  }

  override def nonAuthenticatedPhase1: Route =
    onSuccess(openUserSession.underlying) {
      case session if session.isSuccess =>
        val s = session.toOption.get
        setCookie(
          HttpCookie(PrivateKeyName, s.privateKey, path = Some("/"), domain = Some(host), httpOnly = true),
          HttpCookie(IndexName, s.index, path = Some("/"), domain = Some(host), httpOnly = true)
        ) {
          redirect(buildUjiLoginUri(callbackWhenLoggedIn, s.index), StatusCodes.Found)
        }

      case failedSession =>
        complete("An error has occurred when opening a session with the server UJI" + failedSession)
    }

  override def nonAuthenticatedPhase2(token: String): Route =
    requestUri { uri =>
      cookie(PrivateKeyName) { cookie =>
        decryptUjiToken(token, cookie.content) match {
          case Success(rawToken) =>
            setCookie(
              HttpCookie(SessionName, rawToken, path = Some("/"), domain = Some(host), httpOnly = true)
            ) {
              val safeUri = removeUjiTokFromUri(uri)
              redirect(safeUri, StatusCodes.Found)
            }

          case Failure(errors) => handleErrorsAndComplete(errors)
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

  private[uji] def handleErrorsAndComplete[T](errors: UjiErrors): Route = {
    errors foreach (e => log.error(e.friendlyMessage))
    complete("Error when resolving from request with UJITok\n")
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
  private def openUserSession: XmlrpcResponse[UserSession] =
    Xmlrpc.invokeMethod[Empty, Seq[String]](GetPrivateKeyAndIndex) map ( l =>
      UserSession(l.head, l.tail.head)
    )

  case class LoginConfirmation(authenticatedUsername: String, token: String) {
    lazy val isOk = authenticatedUsername.trim.nonEmpty && token.trim.nonEmpty
  }
  
  private def isStillLogged(token: String): XmlrpcResponse[LoginConfirmation] =
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
  protected def decryptUjiToken(encodedToken: String, privateKey: String): Validation[UjiErrors, String] = {

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
  private def buildUjiLoginUri(callback: Uri, index: String)(implicit language: UserLanguage): Uri =
    UjiLoginEndpoint withQuery (
        "Url" -> callback.toString, "lang" -> language.code, "ident" -> identifier, "dimitri" -> index
    )

  /**
   * Building the redirect url to the Uji logout endpoint.
   *
   * @param callback Uri we want the Uji Server to redirect after the authentication process
   *                  It's not percent-encoded because it's not required following the the RFC3986.
   */
  private def buildUjiLogoutUri(callback: Uri)(implicit language: UserLanguage): Uri =
    UjiLogoutEndpoint withQuery (
        "Url" -> callback.toString, "ident" -> identifier, "lang" -> language.code
      )

  /**
   * This method removes the UJITok parameter the user sends to the server when has been
   * authenticated against the UJI Server.
   *
   * @param toClient Uri sent by the user
   * @return Uri sent back to the client
   */
  private def removeUjiTokFromUri(toClient: Uri): Uri =
    toClient withQuery toClient.query.filterNot(_._1 == TokenLabel)
}
