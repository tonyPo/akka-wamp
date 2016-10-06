package akka.wamp.router

import akka.actor._
import akka.http.scaladsl._
import akka.stream._
import akka.stream.scaladsl._
import akka.wamp._
import akka.wamp.serialization._

import scala.concurrent._
import scala.util.{Failure, Success}

/**
  * INTERNAL API
  * 
  * The transport listener actor spawned by the [[ExtensionManager]]
  * each time it executes [[Wamp.Bind]] commands
  */
private class TransportListener extends Actor {
  
  /** The execution context */
  private implicit val ec = context.system.dispatcher

  /** The actor materializer for Akka Stream */
  // TODO close the materializer at some point
  private implicit val materializer = ActorMaterializer()

  /** Router configuration */
  private val config = context.system.settings.config.getConfig("akka.wamp.router")

  /**
    * The TCP interface (default is 127.0.0.1) to bind to
    */
  val iface = config.getString("iface")

  /**
    * The TCP port number (default is 8080) to bind to
    */
  val port = config.getInt("port")

  /**
    * The boolean switch (default is false) to validate against 
    * strict URIs rather than loose URIs
    */
  val validateStrictUris = config.getBoolean("validate-strict-uris")

  /**
    * The boolean switch to disconnect those peers that 
    * send invalid messages.
    */
  val disconnectOffendingPeers = config.getBoolean("disconnect-offending-peers")

  
  /** The serialization flows */
  // TODO https://github.com/angiolep/akka-wamp/issues/12
  private val serializationFlows = new JsonSerializationFlows(validateStrictUris, disconnectOffendingPeers)

  
  private var binding: Http.ServerBinding = _
  
  /**
    * Handle BIND and UNBIND commands
    */
  override def receive: Receive = {
    case cmd @ Wamp.Bind(router) => {
      val binder = sender()
      val serverSource: Source[Http.IncomingConnection, Future[Http.ServerBinding]] =
        Http(context.system).
          bind(iface, port)

      // when serverSource fails because of very dramatic situations 
      // such as running out of file descriptors or memory available to the system
      val reactToTopLevelFailures: Flow[Http.IncomingConnection, Http.IncomingConnection, _] =
        Flow[Http.IncomingConnection].
          watchTermination()((_, termination) => termination.onFailure {
            case cause => 
              binder ! Wamp.CommandFailed(cmd, cause)
          })

      val handleConnection: Sink[Http.IncomingConnection, Future[akka.Done]] =
        Sink.foreach { conn =>
          val handler = context.actorOf(ConnectionHandler.props(router, serializationFlows))
          handler ! conn
        }

      serverSource
        .via(reactToTopLevelFailures)
        .to(handleConnection)
        .run()
        .onComplete {
          case Success(b) =>
            this.binding = b
            val transport = config.getString("transport")
            assert(b.localAddress.getHostString == iface)
            val port = b.localAddress.getPort
            val path = config.getString("path")
            val url = s"$transport://$iface:$port/$path"
            binder ! Wamp.Bound(self, url)
            
          case Failure(cause) =>
            binder ! Wamp.CommandFailed(cmd, cause)
        }
    }

    case cmd @ Wamp.Unbind =>
      this.binding.unbind()
      context.stop(self)
  }
}

/**
  * INTERNAL API
  */
private[wamp] object TransportListener {
  /**
    * Factory for [[TransportListener]] instances
    */
  def props() = Props(new TransportListener())
}