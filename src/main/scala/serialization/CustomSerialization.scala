package serialization

import akka.actor.{ActorSystem, Props}
import akka.serialization.Serializer
import com.typesafe.config.ConfigFactory
import spray.json._

case class Person(name: String, age: Int)

class PersonSerializer extends Serializer {

    val separator = "//"

    /**any number*/
    override def identifier: Int = 73741 // unique

    /**object to array of bytes*/
    override def toBinary(o: AnyRef): Array[Byte] = o match {
            case person @ Person(name, age) =>
            // [name||age] serialization will look like this and this needs to be turned to Array[Bytes]
                println(s"Serializing $person")
                s"[$name$separator$age]".getBytes() // Inventing your own format
            case _ => throw new IllegalArgumentException("only Persons are supported or this serializer")
        }

    /**array of bytes to an object*/
    override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
        val string = new String(bytes)
        val values = string.substring(1, string.length-1).split(separator) // [name||age]
        val name = values(0)
        val age = values(1).toInt
        val person = Person(name, age)
        println(s"Deserialized $person")
        person
    }

    /** includeManifest()
     * Is a flag that tells us whether this manifest: Option of Class.
     * will be passed to the serializer when we want to recover our object.
     *
     * The Manifest will be some kind of a hint so that we know which class
     * to instantiate when we are recovering and object from an array of bytes */
    override def includeManifest: Boolean = false // we are serializing Person not any other class
} /*CustomSerialization.conf*/


/*spray json will be used here to automatically convert object into json and send json string over the wire*/
class PersonJsonSerializer extends Serializer with DefaultJsonProtocol {
    implicit val personFormat: RootJsonFormat[Person] = jsonFormat2(Person)

    override def identifier: Int = 12821

    override def toBinary(o: AnyRef): Array[Byte] = o match {
        case p: Person => // object to json
            val json: String = p.toJson.prettyPrint
            println(s"Converting $p to $json")
            json.getBytes()
        case _ => throw new IllegalArgumentException("only Persons are supported or this serializer")
    }

    override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
        val string = new String(bytes)
        val person = string.parseJson.convertTo[Person]
        println(s"Deserialized $string to $person")
        person
    }

    override def includeManifest: Boolean = false
}

object CustomSerialization_Local extends App {
    val config = ConfigFactory.parseString(
        """
          |akka.remote.artery.canonical.port = 2551
          |""".stripMargin
    ).withFallback(ConfigFactory.load("CustomSerialization.conf"))

    val system = ActorSystem("LocalSystem", config)
    val actorSelection = system.actorSelection("akka://RemoteSystem@localhost:2552/user/remoteActor")

    actorSelection ! Person("Bob", 26)
    // Serializing Person(Bob,26), local serializes the bytes and sends the bytes over the wire
}

object CustomSerialization_Remote extends App {
    val config = ConfigFactory.parseString(
        """
          |akka.remote.artery.canonical.port = 2552
          |""".stripMargin
    ).withFallback(ConfigFactory.load("CustomSerialization.conf"))

    val system = ActorSystem("RemoteSystem", config)
    val simpleActor = system.actorOf(Props[SimpleActor], "remoteActor")
    // Deserialized Person(Bob,26), deserialized and message gets sent to the actor that says Received: Person(Bob,26)

    // spin up Remote and then local
}

/**
 * The Person message was serialized by the Local app, sent over the wire
 * Deserialized at the destination jvm and then
 * sent to remoteActor*/

object CustomSerialization_Persistence extends App { // postgres config
    val config = ConfigFactory.load("persistentStores").getConfig("postgresStore")
        .withFallback(ConfigFactory.load("CustomSerialization.conf"))

    val system = ActorSystem("PersistenceSystem", config)
    val simplePersistentActor = system.actorOf(SimplePersistentActor.props("person-json"), "personJsonActor")

    //simplePersistentActor ! Person("Alice", 23)
    // spin up Remote and then this app... serialized

    // stop app and comment ! and run the app to check recovery
    // now deserializer comes in and RECOVERED Person(Alice,23), RECOVERED RecoveryCompleted
}
