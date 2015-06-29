package ujisso

import akka.util.Timeout
import spray.http.Uri
import spray.routing.Route

trait UjiProtocol {
  // Authentication protocol
  def nonAuthenticatedPhase1: Route
  def nonAuthenticatedPhase2(token: String): Route
  def authenticated(encryptedToken: String): Route
  def logout: Route

  // The user has to implement the following method and variables
  def hookWhenUserAuthenticated(token: String, username: String): Route
  val callbackWhenLoggedIn: Uri
  val callbackWhenLoggedOut: Uri

  // Additional config -> Timeout for the XML-RPC calls
  val timeout: Timeout
}
