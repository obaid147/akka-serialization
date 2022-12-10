package playground

import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor.{Actor, ActorLogging, ActorSystem, OneForOneStrategy, Props, Stash, SupervisorStrategy}
import akka.persistence.PersistentActor
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

object SimpleActor {
    case object change

    case class StashThis(message: String)

    case object CreateChild
}

class SimpleActor extends Actor with Stash {

    import SimpleActor._

    override def receive: Receive = {
        case CreateChild =>
        /*val child = context.actorOf(Props[ChildActor], "child")
        child ! "Hello"*/
        case StashThis(_) =>
            stash()
        case "changeHandlerNow" =>
            unstashAll()
            context.become(anotherHandler)
        case message: String =>
            println(s"${self.path.name} received: $message")
            sender() ! message
        case change => context.become(anotherHandler)
    }

    def anotherHandler: Receive = {
        case message: String =>
            println(s"In another message handler $message")
            context.unbecome()
        case StashThis(msg) =>
            println(s"In another message handler $msg")
            context.unbecome()
    }

    override def preStart(): Unit = println(s"${self.path.name} is starting")

    override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
        case _: RuntimeException => Restart // if RuntimeException the RESTART the child
        case _ => Stop // if any other exception, STOP the child
    }
}

class SimplePersistentActor extends PersistentActor with ActorLogging {

    override def persistenceId: String = "simple-persistent-actor"

    override def receiveCommand: Receive = {
        case msg => persist(msg) { _ =>
            log.info(s"I have persisted $msg")
        }
    }

    override def receiveRecover: Receive = {
        case event => log.info(s"RECOVERED $event")
    }
}

/*class ChildActor extends Actor {
        override def receive: Receive = {
            case msg =>
                println(s"${self.path.name} received $msg")
                sender() ! "Child says hi!"
        }
        override def preStart(): Unit = println(s"${self.path.name} is starting")

    }*/

object AkkaRecap extends App {

    import SimpleActor._
    // actor encapsulation
    val system = ActorSystem("AkkaRecap")
    val simpleActor = system.actorOf(Props[SimpleActor], "simpleActor")

    simpleActor ! "Test 1" // sends aync message to actor and actor receives it at some point in future.

    /*
    * messages are sent asynchronously
    * many actors  can share a few dozen threads.
    * each message is handled atomically. No Race condition No locking resources.
    */

    /*
    simpleActor ! change
    simpleActor ! "what?"*/

    println("-----------")
    // stashing
    /*simpleActor ! StashThis("Stash 1") // put aside for later
    simpleActor ! "Test 2"
    simpleActor ! StashThis("Stash 2") // put aside for later
    simpleActor ! "changeHandlerNow"*/

    println("------------") // unbecome to change the handler back to SimpleActor's receive method
    //Actors can spawn other actors
    simpleActor ! CreateChild

    // Guradians
    /* /system, /user, / = root guardian is parent of every actor in actor system.*/

    /*Actor have defined lifecycle
    * - Started, stopped, resumed, restarted, suspended
    * - methods like preStart etc which we can override
    */

    //Stopping
    /*Stopping by context.Stop or externally using special messages:- PoisonPill or Kill */
    /*Thread.sleep(200)
    simpleActor ! PoisonPill*/

    // Logging
    /*by mixing ActorLogging with the Actor:- info, Debug, warning, error like methods*/

    // supervision
    /*How Parent Actor respond to child actor failures
    * each actor has method
    * - supervisorStrategy()
    * - multiple strategies:- OnForOneStrategy ... with decisions based on what to do when child throws
    *                         stop the child, restart child.....*/


    // Configure akka infrastructure.... dispatchers, routers, mailboxes

    // schedulers
    import system.dispatcher

    import scala.concurrent.duration.DurationInt // manager of threads which implements ExecutionContext interface

    system.scheduler.scheduleOnce(2.seconds) {
        simpleActor ! "Delayed Happy birthday"
    }

    // akka-patterns including FSM and AskPattern
    import akka.pattern.ask
    implicit val timeout = Timeout(3.seconds)
    val future = simpleActor ? "question"
    //val future: Future[String] = (simpleActor ? "question").mapTo[String]
    /* returns a future and will contain response from actor as reaction to "question"
        This future will be complete with the first reply from the actor and all other replies are discarded.
        If actor doesn't reply within 3 seconds, future fails with timeout exception
    */

    // Common practice is to use ask pattern with  PIPE pattern.
    import akka.pattern.pipe
    val anotherActor = system.actorOf(Props[SimpleActor], "anotherActor")
    // above futures result can be sent to this anotherActor
    future.mapTo[String].pipeTo(anotherActor)
    // this means when future completes, it's value is being sent to anotherActor as a message

    // application.conf
}

/*Sending messages across JVM*/
object AkkaRecap_Local extends App {
    val config = ConfigFactory.parseString(
        """
          |akka.remote.artery.canonical.port=2551
          |""".stripMargin
    ).withFallback(ConfigFactory.load())

    val system = ActorSystem("LocalSystem", config)
    val actorSelection = system.actorSelection("akka://RemoteSystem@localhost:2552/user/remoteActor")
    actorSelection ! "Hello from afar!"
}

object AkkaRecap_Remote extends App {
    val config = ConfigFactory.parseString(
        """
          |akka.remote.artery.canonical.port=2552
          |""".stripMargin
    ).withFallback(ConfigFactory.load())

    val system = ActorSystem("RemoteSystem", config)
    val remoteActor = system.actorOf(Props[SimpleActor], "remoteActor")
}

/** spin up AkkaRecap_Remote and then AkkaRecap_Local and check logs for message on AkkaRecap_Local*/


// persistent actor
object AkkaRecap_Persistence extends App {
    val config = ConfigFactory.load("persistentStores.conf").getConfig("postgresStore")
    val system = ActorSystem("PersistenceSystem", config)
    val simplePersistentActor = system.actorOf(Props[SimplePersistentActor], "simplePersistentActor")

    simplePersistentActor ! "Hello persistence actor"
    simplePersistentActor ! "I hope you are persisting what i say"

    // spin up postgres container and run the app and from cygwin ./psql.sh the select * from public.journal;

}

/**
 * Now, the same thing with remoting applies to persistence in the sense that if your actors persist
    anything else other than a string or an Int or primitive data types, for example, persisting your
    own events in order to reach the persistence store, you'll need to go through serialization to persist
    your events And that's where this course comes in.
 * */
