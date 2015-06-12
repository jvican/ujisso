package api.uji

import java.util.concurrent.TimeUnit

import akka.event.Logging
import org.specs2.matcher.Matchers
import org.specs2.mutable.Specification
import spray.http.{Uri, HttpHeader, HttpCookie}
import spray.http.HttpHeaders.Cookie
import spray.routing.Route
import spray.testkit.Specs2RouteTest

import scala.concurrent.duration.FiniteDuration

trait MyUjiAuthentication extends UjiAuthentication {
  override def hookWhenUserAuthenticated(username: String): Route = complete(s"User $username is already logged in the system")
  override lazy val callbackWhenLoggedIn: Uri = Uri("http://test.uji.es/dashboard")
  override lazy val callbackWhenLoggedOut: Uri = Uri("http://test.uji.es/index")
}

class UjiAuthenticationTest extends Specification with Specs2RouteTest with MyUjiAuthentication with Matchers {
  override implicit def actorRefFactory = system
  override val log = Logging(system, "UjiTest")

  // Time to connect to the XML-RPC UJI server, sometimes slow
  implicit val routeTestTimeout = RouteTestTimeout(FiniteDuration(5, TimeUnit.SECONDS))

  val ujiTokLabel = "UJITok"
  val ujiTok = "BAQFDlAGVF5bAA9SDwICVQddBwUIVw4GBlVUDwAPBVVbBg1bBFJQWw" // encrypted and base64-encoded
  val rawToken = "1568bb6fbb97930cadea8f87372966cf93585aa9" // decrypted

  val privateKey = "51362db89b6e6126f9bd01615bf669f3b58c131b"
  val indexOfPrivateKey = "10efd1c918c0b5e53353dfa7406aeaa5e695f337"

  val LSMSessionLabel = "LSMSession"
  val LSMSessionPKLabel = LSMSessionLabel + "PK"
  val LSMSessionIDXLabel = LSMSessionLabel + "IDX"
  val SessionCookie = HttpCookie(LSMSessionLabel + identifier, rawToken)
  val LSMSessionPK = HttpCookie(LSMSessionPKLabel + identifier, privateKey)
  val LSMSessionIDX = HttpCookie(LSMSessionIDXLabel + identifier, indexOfPrivateKey)

  def cookiesFrom(headers: List[HttpHeader]) = headers filter (h => h.is("set-cookie"))
  
  def redirectionLocationFrom(headers: List[HttpHeader]) = headers find (_.is("location")) map (_.value) get

  "The Uji Authentication module" should {

    "have a correct identifier" in {
      identifier must not be 'empty
      identifier must not contain "."
      identifier === "test"
    }

    "return 2 cookies (private key and index) and redirect to the UJI SSO if the user is not authenticated" in {
      Get("/uji/login") ~> routesUjiAuth ~> check {
        status.intValue === 302

        val cookies = cookiesFrom(headers)
        cookies.head.value must startWith (LSMSessionPKLabel + identifier)
        cookies.tail.head.value must startWith (LSMSessionIDXLabel + identifier)
      }
    }

    "decrypt correctly the token" in {
      decryptUjiToken(ujiTok, privateKey).getOrElse("") === rawToken
    }

    "retrieve UJITok from the URL, redirect to the same url without it and set the session cookie" in {
      val urlRedirectedFromUjiSSO = "/uji/login?" + ujiTokLabel + "=" + ujiTok

      Get(urlRedirectedFromUjiSSO) ~> Cookie(LSMSessionPK, LSMSessionIDX) ~> routesUjiAuth ~> check {
        status.intValue === 302
        redirectionLocationFrom(headers) must not contain ujiTokLabel
        val sessionCookie = cookiesFrom(headers) filter (_.value startsWith LSMSessionLabel + identifier)
        sessionCookie must not be empty
      }
    }

    /**
     * This is only a dummy test to show how actually the last phase of the UJI SSO authentication
     * process works. However, this is going to give always an error in this test because the uji
     * token used is not active in the SSO.
     */
    "get the session cookie and authenticate correctly" in {
      Get("/uji/login") ~> Cookie(SessionCookie, LSMSessionPK, LSMSessionIDX) ~> routesUjiAuth ~> check {
        /* Testing the behaviour to carry out once authentication has succeeded.
         * This behaviour is specified by the users of the library by overriding
         * hookWhenLoggedIn. */
        true
      }
    }

    "log out successfully by deleting all the uji cookies and redirecting to the UJI logout site" in {
      Get("/uji/logout") ~> Cookie(SessionCookie, LSMSessionPK, LSMSessionIDX) ~> routesUjiAuth ~> check {
        status.intValue === 302
      }
    }
  }
}
