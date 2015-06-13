# Authentication with the UJI SSO in Scala
Suppose you feel inspired and excited about taking a new adventure. You come accross a cool language called Scala, you become proficient in it and you want to use it everywhere,
including your internship project. Well, ok, that was my case. But it can be yours too. So here you have a library I have developed to allow authentication against the UJI SSO.
  
This project has been ported from Java (servlets api) and php code, the only two existing alternatives until now. They were developed by the UJI itself.
However, this library is totally __async__, built up on top of [Spray](http://www.spray.io) and [Akka](http://www.akka.io). It has a lot of documentation
because the existing alternatives were not very clear in the protocol specification. Now, if you want you can built your own library in your favorite language without any excuse. Just code.

## Tell me the secret, how to do it

Mixin the Uji Authentication trait and implement _hookWhenAuthenticated_, the function the library will execute when the authentication has succeed. After that, you have to declare a callback as an [URI](http://spray.io/documentation/1.1-SNAPSHOT/api/index.html#spray.http.Uri$), in order to redirect to that callback when the user has logged out from the UJI SSO. This callback is _callbackWhenLoggedOut_.
  
Then use _ujiRoutes_, which is a variable of type [Routes](http://spray.io/documentation/1.2.2/spray-routing/key-concepts/routes/), inside your server routes in spray-routing.
