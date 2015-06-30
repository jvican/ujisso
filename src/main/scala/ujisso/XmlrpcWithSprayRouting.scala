package ujisso

import spray.routing.Route
import spray.routing.directives.OnCompleteFutureMagnet
import xmlrpc.XmlrpcResponse
import xmlrpc.protocol.Deserializer.{Fault, Deserialized, AnyErrors}

import scala.concurrent.ExecutionContext
import scala.language.postfixOps

import scalaz.Scalaz._
import scalaz.Failure
import scalaz.Success

object XmlrpcWithSprayRouting {
  implicit class WithRetryFeature[S](f: () => XmlrpcResponse[S]) {
    def retry(runSuccess: S => Route, runFailure: AnyErrors => Route, times: Int)
             (implicit ec: ExecutionContext): Route = {

      def retryLogic(lastFailure: AnyErrors): Route = times match {
        case n if n > 0 => retry(runSuccess, runFailure, times - 1)
        case 0 => runFailure(lastFailure)
        case n if n < 0 => throw new Error("Times of retry cannot be negative")
      }

      OnCompleteFutureMagnet.apply(f().underlying)(ec) {
        case s: scala.util.Success[Deserialized[S]] => s.get match {
          case Success(success) => runSuccess(success)
          case Failure(failure: AnyErrors) => retryLogic(failure)
        }

        case scala.util.Failure(exception: Throwable) => retryLogic(Fault(-1, exception.getMessage).wrapNel)
      }
    }
  }
}
