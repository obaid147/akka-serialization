package serialization

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.serialization.Serializer
import com.sksamuel.avro4s.{AvroInputStream, AvroOutputStream, AvroSchema}
import com.typesafe.config.ConfigFactory
import serialization.Datamodel.ProtobufVote

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.UUID
import scala.util.Random

/** Avro vs Kryo vs protobuf vs java Serializers
 * Speed: send messages across JVMs
 * 1. 1 Lakh messages (100K)
 * 2. 10 Lakh messages (1M)
 *
 * no true network involved - minimal latency.
 *
 * Memory efficiency:
 * persist 10 thousand events to postgres store (10K) by switching each serializer
 * measure avg message size
 */

// voting scenario with socialSecurityNumber and candidate
case class Vote(ssn: String, candidate: String)
case object VoteEnd

object VoteGenerator {
    val random = new Random()
    val candidates = List("alice", "bob", "charlie", "martin")

    def getRandomCandidate: String = candidates(random.nextInt(candidates.length))

    def generateVotes(count: Int) = (1 to count).map(_ => Vote(UUID.randomUUID().toString, getRandomCandidate))

    def generateProtobufVotes(count: Int) = (1 to count).map { _ =>
        ProtobufVote.newBuilder()
            .setSsn(UUID.randomUUID().toString)
            .setCandidate(getRandomCandidate)
            .build()
    }
}

class VoteAvroSerializer extends Serializer {

    val voteSchema = AvroSchema[Vote]

    override def identifier: Int = 52375

    override def toBinary(o: AnyRef): Array[Byte] = o match {
        case vote: Vote  =>
            val baos = new ByteArrayOutputStream()
            val avroOutputStream = AvroOutputStream.binary[Vote].to(baos).build(voteSchema)
            avroOutputStream.write(vote)
            avroOutputStream.flush()
            avroOutputStream.close()
            baos.toByteArray
        case _ => throw new IllegalArgumentException("We only support votes in this benchmark serializer")
    }

    override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
        val inputStream = AvroInputStream.binary[Vote].from(new ByteArrayInputStream(bytes)).build(voteSchema)
        val voteIterator: Iterator[Vote] = inputStream.iterator
        val vote = voteIterator.next()
        inputStream.close()

        vote
    }

    override def includeManifest: Boolean = true
}

class VoteAggregator extends Actor with ActorLogging {
    override def receive: Receive = ready

    def ready: Receive = {
        case _: Vote | _: ProtobufVote => context.become(online(1, System.currentTimeMillis()))
    }

    def online(voteCount: Int, originalTime: Long): Receive = {
        case _: Vote | _: ProtobufVote =>
            context.become(online(voteCount+1, originalTime))
        case VoteEnd =>
            val duration = (System.currentTimeMillis() - originalTime) * 1.0 / 1000
            log.info(f"Received $voteCount votes in $duration%5.3f seconds")
            context.become(ready)
    }
}

object VotingStation extends App {
    val config = ConfigFactory.parseString(
        """
          |akka.remote.artery.canonical.port = 2551
          |""".stripMargin
    ).withFallback(ConfigFactory.load("serializersBenchmark.conf"))

    val system = ActorSystem("VotingStation", config)
    val actorSelection = system.actorSelection("akka://VotingCentralizer@localhost:2552/user/voteAggregator")

    // for -java -avro -kryo
        /*val votes = VoteGenerator.generateVotes(1000000)
        votes.foreach(actorSelection ! _)
        actorSelection ! VoteEnd*/

    // for protobuf
    val votes = VoteGenerator.generateProtobufVotes(1000000)
    votes.foreach(actorSelection ! _)
    actorSelection ! VoteEnd
}

object VotingCentralizer extends App {
    val config = ConfigFactory.parseString(
        """
          |akka.remote.artery.canonical.port = 2552
    """.stripMargin)
        .withFallback(ConfigFactory.load("serializersBenchmark"))

    val system = ActorSystem("VotingCentralizer", config)
    val votingAggregator = system.actorOf(Props[VoteAggregator], "voteAggregator")
}

/**
 * 100000(1L) ... change between serialization-bindings "serialization.Vote" = java/avro/kryo in .conf and compare results
 *  -java:- Received 100000 votes in 2.287 seconds
 *  -avro:- Received 100000 votes in 2.014 seconds
 *  -kryo:- Received 100000 votes in 1.555 seconds
 *  -protobuf:- Received 100000 votes in 1.419 seconds
 *
 * 1000000(10L)
 *  -java:- Received 1000000 votes in 11.533 seconds
 *  -avro:- Received 1000000 votes in 7.732 seconds
 *  -kryo:- Received 1000000 votes in 6.274 seconds
 *  -protobuf:- Received 100000 votes in 7.289 seconds
 * */

object SerializersBenchmark_Persistence extends App { // postgres config
    val config = ConfigFactory.load("persistentStores").getConfig("postgresStore")
        .withFallback(ConfigFactory.load("serializersBenchmark"))

    val system = ActorSystem("PersistenceSystem", config)
    // change persistence id=> java:- benchmark-java, avro:- benchmark-avro, kryo:- benchmark-kryo protobuf:-"benchmark-protobuf".
    //    and switch "serialization.Vote" = java // set up for java, avro, kryo also...
    val simplePersistentActor = system.actorOf(SimplePersistentActor.props("benchmark-protobuf", shouldLog = false), "benchmark")
    // TODO change persistence id between runs
    /*before running    delete from public.journal;*/
    /*val votes = VoteGenerator.generateVotes(10000)
    votes.foreach(simplePersistentActor ! _)*/

    val votes = VoteGenerator.generateProtobufVotes(10000) // persistenceId="benchmark-protobuf"
    votes.foreach(simplePersistentActor ! _)
    /*select count(*) from public.journal; for every serializer


    * select avg(length(message)), persistence_id from public.journal group by persistence_id;

        Memory Test result
        avg          |   persistence_id
        ----------------------+--------------------
        128.2268000000000000 | benchmark-avro
        195.2457000000000000 | benchmark-java
        150.2206000000000000 | benchmark-protobuf
        111.2408000000000000 | benchmark-kryo

    */
}
