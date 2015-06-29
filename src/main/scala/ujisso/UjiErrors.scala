package ujisso

import spray.routing.{Rejection, RejectionHandler}

import scalaz.NonEmptyList

sealed trait UjiError {
  val friendlyMessage: String
}

object UjiError {
  type UjiErrors = NonEmptyList[UjiError]
}

case class InvalidToken(encodedToken: String) extends UjiError {
  override val friendlyMessage: String = s"An error has occurred when decoding the token $encodedToken"
}

case object DecryptionLengthMismatch extends UjiError {
  override val friendlyMessage: String = s"Token and private key must have the same length to decrypt with one time pad (OTP)."
}

trait UjiRejections {
  trait FailureMessage { val failureMessage: String }

  import spray.routing.Directives._
  import spray.http.StatusCodes._

  case object NewSessionFailure extends Rejection with FailureMessage {
    override val failureMessage: String = "A new session couldn't be opened with the server."
  }

  /**
   * This is the general error for `DecryptionLengthMismatch` and `InvalidToken`. We don't want
   * the user to see these internal protocol-specific vulnerable errors.
   */
  case object TokenDecryptionFailure extends Rejection with FailureMessage {
    override val failureMessage: String = "The token couldn't be decrypted or the result is incorrect."
  }

  case object EmptyLoginConfirmation extends Rejection with FailureMessage {
    override val failureMessage: String = "The empty token couldn't be decrypted or the result is incorrect."
  }

  object UjiRejectionHandler {
    def apply(): RejectionHandler = RejectionHandler {
      case NewSessionFailure :: _ => complete(InternalServerError, NewSessionFailure.failureMessage)
      case TokenDecryptionFailure :: _ => complete(InternalServerError, TokenDecryptionFailure.failureMessage)
      case EmptyLoginConfirmation :: _ => complete(InternalServerError, EmptyLoginConfirmation.failureMessage)
    }
  }
}
