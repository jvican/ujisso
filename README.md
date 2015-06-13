# UJI SSO authentication in Scala [![Build Status](https://travis-ci.org/jvican/ujisso.svg?branch=master)](https://travis-ci.org/jvican/ujisso)

Suppose you feel inspired and excited about taking a new adventure. You come accross a cool language called Scala, you become proficient in it and you want to use it everywhere,
including your internship project. Well, ok, that was my case. But it can be yours too. So here you have a library I have developed to allow authentication against the UJI SSO (Jaume I University, Spain).
  
This minimal module has been ported from Java (servlets api) and php code, the only two existing alternatives until now. They were developed by the UJI itself.
However, this library is totally __async__, built up on top of [Spray](http://www.spray.io) and [Akka](http://www.akka.io). 
  
A good points is that there is a lot of documentation (check out source code and tests)
because the existing alternatives were not very clear in the protocol specification. Now, if you want you can built your own library in your favorite language (Clojure, Ruby, etc) without any excuse. Just code.

## Tell me the secret, how to use it

Mixin the Uji Authentication trait and implement _hookWhenAuthenticated_, the function the library will execute when the authentication has succeed. After that, you have to declare a callback as an [URI](http://spray.io/documentation/1.1-SNAPSHOT/api/index.html#spray.http.Uri$), in order to redirect to that callback when the user has logged out from the UJI SSO. This callback is _callbackWhenLoggedOut_.
  
Having imported the dependencies of Akka and Spray for [server routing](http://spray.io/documentation/1.2.3/spray-routing/dependencies/) use _ujiRoutes_, which is of type [Routes](http://spray.io/documentation/1.2.2/spray-routing/key-concepts/routes/), inside your server routes in spray-routing.

### Example
```scala
import spray.routing.SimpleRoutingApp

object Main extends App with SimpleRoutingApp {
  implicit val system = ActorSystem("my-system")

  startServer(interface = "localhost", port = 8080) {
    path("hello") {
      get {
        complete {
          <h1>goodbye</h1>
        }
      }
    } ~ // This is the operator to bind several routes in one
    ujiRoutes // This is the trick that maps /uji/login and /uji/logout
  }
}
```
  
With this setup, if a client makes a request to _/uji/login_, your server will log in the user (if it's not already) and when this operation has succeeded, the function _hookWhenAuthenticated_ will be executed. You have to implement this function as you want. The common thing is to redirect to a private resource only available to authenticated users, as a profile page or a dashboard. If a user makes a request _/uji/logout_, he will be logged out from the UJI SSO.
