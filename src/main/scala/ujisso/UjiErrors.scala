package api.uji

import scalaz.NonEmptyList

sealed trait UjiError {
  val friendlyMessage: String
}

object UjiError {
  type UjiErrors = NonEmptyList[UjiError]
}

case class InvalidToken(encodedToken: String) extends UjiError {
  override val friendlyMessage: String = s"An error has occurred when decoding the token "
}

object DecryptionLengthMismatch extends UjiError {
  override val friendlyMessage: String =
    s"Token and private key must have the same length to decrypt with one time pad (OTP)."
}
