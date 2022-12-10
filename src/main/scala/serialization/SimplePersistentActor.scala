package serialization

import akka.actor.{ActorLogging, Props}
import akka.persistence.PersistentActor

class SimplePersistentActor(override val persistenceId: String, shouldLog: Boolean = true) extends PersistentActor
    with ActorLogging{

    override def receiveCommand: Receive = {
        case msg => persist(msg) { e =>
            if (shouldLog)
                log.info(s"PERSISTED $msg")
        }
    }

    override def receiveRecover: Receive = {
        case event =>
            if (shouldLog)
                log.info(s"RECOVERED $event")
    }
}

object SimplePersistentActor {
    def props(persistenceId: String, shouldLog: Boolean = true): Props =
        Props(new SimplePersistentActor(persistenceId, shouldLog))
}
