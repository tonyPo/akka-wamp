package akka.wamp.client

import org.scalamock.scalatest.MockFactory

class SessionSpec extends ClientFixtureSpec with MockFactory {

  "A client session" should "reply GOODBYE upon receiving GOODBYE from router" in { f =>
    // TODO https://github.com/angiolep/akka-wamp/issues/11
    pending
    f.withSession { session =>
      // make the router send Goodbye("wamp.error.system_shutdown")
      // routerManager ! ShutDown
      // f.listener.expectMsg(ShutDown)
      // ???
    }
  } 
  
  
  it should "succeed close by sending GOODBYE and expecting to receive GOODBYE in response" in { f =>
    f.withSession { session =>
      whenReady(session.close()) { _ =>
        assert(true)
      }
    }
  }
}
