package api.uji

import spray.http.Uri
import spray.routing.Route

trait UjiProtocol {
  // Authentication methods
  def nonAuthenticatedPhase1: Route
  def nonAuthenticatedPhase2(token: String): Route
  def authenticated(encryptedToken: String): Route
  def logout: Route

  // The user has to implement the following method and variables
  def hookWhenUserAuthenticated(username: String): Route
  val callbackWhenLoggedIn: Uri
  val callbackWhenLoggedOut: Uri
}
