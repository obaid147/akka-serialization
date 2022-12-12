package serialization

import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import serialization.Datamodel.OnlineStoreUser



/**
 * Protocol Buffers google's serialization framework:-
 * very fast
 * memory-efficient
 * schema generation and validation. "schema based" via proto files (IDL) interface definition language
 * best for schema evolution (structure changes, problems with persistence that protobuf solves)
 * already implemented Akka Protobuf serializer.
 *
 * cons:-
 * needs dedicated code generator to translate proto files from IDL into actual code that we can use.
 * It does not support scala out of the box, we generate java files only.
 *
 * https://github.com/protocolbuffers/protobuf/releases
 * https://developers.google.com/protocol-buffers/docs/javatutorial
 *
 * ./main/exec/protoc --java_out=main/java main/proto/datamodel.proto
 * cygwin command to generate Datamodel.java
 * */
object ProtobufSerialization_Local extends App {
    val config = ConfigFactory.parseString(
        """
          |akka.remote.artery.canonical.port = 2551
    """.stripMargin)
        .withFallback(ConfigFactory.load("protobufSerialization"))

    val system = ActorSystem("LocalSystem", config)
    val actorSelection = system.actorSelection("akka://RemoteSystem@localhost:2552/user/remoteActor")

    val onlineStoreUser: OnlineStoreUser = OnlineStoreUser.newBuilder()
        .setId(12321)
        .setUserName("obaid-thejvm")
        .setUserEmail("obaid@example.com")
        .build()

    actorSelection ! onlineStoreUser
}

object ProtobufSerialization_Remote extends App {
    val config = ConfigFactory.parseString(
        """
          |akka.remote.artery.canonical.port = 2552
    """.stripMargin)
        .withFallback(ConfigFactory.load("protobufSerialization"))

    val system = ActorSystem("RemoteSystem", config)
    val simpleActor = system.actorOf(Props[SimpleActor], "remoteActor")
}

object ProtobufSerialization_Persistence extends App {
    val config = ConfigFactory.load("persistentStores").getConfig("postgresStore")
        .withFallback(ConfigFactory.load("protobufSerialization"))

    val system = ActorSystem("PersistenceSystem", config)
    val simplePersistentActor = system.actorOf(SimplePersistentActor.props("protobuf-actor"), "protobufActor")

    val onlineStoreUser: OnlineStoreUser = OnlineStoreUser.newBuilder()
        .setId(34564)
        .setUserName("diabo-theAkka")
        .setUserEmail("biabo@example.com")
        .setUserPhone("2231484565")
        .build()

    //simplePersistentActor ! onlineStoreUser
}
