package ujisso

import akka.event.LoggingAdapter
import spray.routing.Directives._
import spray.routing.directives.OnCompleteFutureMagnet
import spray.routing.{Directive1, Route}
import xmlrpc.XmlrpcResponse
import xmlrpc.XmlrpcResponse.WithRetry
import xmlrpc.protocol.Deserializer.{AnyErrors, Deserialized, Fault}

import scala.concurrent.ExecutionContext
import scala.language.postfixOps
import scalaz.Scalaz._

trait SprayRoutingWithXmlrpc {
  implicit val log: LoggingAdapter

  object XmlrpcDirectives {
    def xmlrpcCall[R](f: () => XmlrpcResponse[R], atLeastOneTime: Int = 3)(implicit ec: ExecutionContext): Directive1[Deserialized[R]] = {
      val call: XmlrpcResponse[R] = f.retry(atLeastOneTime)
      OnCompleteFutureMagnet.apply(call.underlying)(ec) flatMap {
        case s: scala.util.Success[Deserialized[R]] => provide(s.get)
        case scala.util.Failure(exception: Throwable) => provide(Fault(-1, exception.getMessage).failureNel)
      }
    }

    def xmlrpcCallAndHandle[R, S](f: () => XmlrpcResponse[R], failure: AnyErrors => S, success: R => S, times: Int = 1)(implicit ec: ExecutionContext): Directive1[S] =
      xmlrpcCall[R](f, times) flatMap {
        case scalaz.Success(succ) => provide(success(succ))
        case scalaz.Failure(errors) => provide(failure(errors))
      }
  }

  def xmlrpcCallAndRoute[R](f: () => XmlrpcResponse[R], failure: AnyErrors => Route, success: R => Route, times: Int = 1)(implicit ec: ExecutionContext): Route =
    XmlrpcDirectives.xmlrpcCall[R](f, times)(ec) {
      case scalaz.Success(succ) => success(succ)
      case scalaz.Failure(errors) => failure(errors)
    }
}
