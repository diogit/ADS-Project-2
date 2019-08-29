import akka.actor.ActorSystem
import akka.actor.ActorRef
import akka.actor.Actor
import akka.actor.Props
import com.typesafe.config.ConfigFactory
import akka.remote._
import collection.JavaConversions._
import scala.concurrent.duration._


object Main {
	def main(args: Array[String]): Unit = {
		if (args.length != 3 && args.length != 4){
			println("[MAIN] Usage: Main <myId> <myIp> <myPort> [numAutomaticTests]")
			System.exit(1)
		}

		val config = ConfigFactory.parseMap(Map("myIp" -> args(1), "myPort" -> args(2)))
					.withFallback(ConfigFactory.parseResources("aplication.conf"))
					.withFallback(ConfigFactory.load()).resolve()
		val actorSystem = ActorSystem("ASDProject", config)

		val myId = args(0).toInt
		val myHostname 	= actorSystem.settings.config.getString("akka.remote.netty.tcp.hostname")
		val myPort 		= actorSystem.settings.config.getString("akka.remote.netty.tcp.port")
		//println("actorSystem.settings.config.getString("akka.remote.use-passive-connections: "+actorSystem.settings.config.getString("akka.remote.use-passive-connections"))
		
		// Create the actors for the protocols
		val multipaxos = actorSystem.actorOf(Props(new MultiPaxos(myId, myHostname, myPort)), name="multipaxos")
		val statemachine = actorSystem.actorOf(Props(new StateMachine(myHostname, myPort)), name="statemachine")
		val app = actorSystem.actorOf(Props(new App(myHostname, myPort)), name="app")
		val client = actorSystem.actorOf(Props(new Client(myHostname, myPort)), name="client")

		// Initalize replica list in multipaxos and the client
		var initialReplicas:Set[String] = Set()
		for (replica <- actorSystem.settings.config.getStringList("custom.replicas")){
			multipaxos ! AddReplica(replica)
			initialReplicas = initialReplicas + replica
		}
		client ! UpdateReplicas(initialReplicas)

		import actorSystem.dispatcher
		// Multipaxos Timers
		val pingLeaderTimer =
			actorSystem.scheduler.schedule(
				5000 milliseconds, //after 5 seconds
				2500 milliseconds, //every 2.5 seconds
				multipaxos,
				TriggerPingLeader()
			)
		// Client Timers
		val updateReplicasTimer =
			actorSystem.scheduler.schedule(
				5000 milliseconds, //after 5 seconds
				4000 milliseconds, //every 4 seconds
				client,
				TriggerAskForReplicas()
			)

		// Test client
		if (args.length == 4){
			println("[CONSOLE] Automatically running "+args(3)+" test ops")
			var numOps:Int = args(3).toInt
			for(i <- 0 until numOps) {
				var pair:Int = i % 2
				if ( pair == 0 ) {
					client ! Write(i.toString, (System.currentTimeMillis).toString)
				} else {
					client ! Read((i - 1).toString)
				}
			}
		}

		// Command interpreter
		var command:String = ""
		do {
			var cmdArgs:Array[String] = readLine().split(" ")
			command = cmdArgs(0)
			command match {
				case "read" | "r" =>
					if (cmdArgs.size < 2) {
						println("[CONSOLE] Usage: read <key>")
					} else {
						client ! Read(cmdArgs(1))
					}
				case "write" | "w" =>
					if (cmdArgs.size < 3) {
						println("[CONSOLE] Usage: write <key> <value>")
					} else {
						client ! Write(cmdArgs(1), cmdArgs.tail.tail.mkString(" "))
					}
				case "add" | "+" =>
					if (cmdArgs.size < 2) {
						println("[CONSOLE] Usage: add <replica>")
					} else {
						client ! Add(cmdArgs(1))
					}
				case "remove" | "-" =>
					if (cmdArgs.size < 2) {
						println("[CONSOLE] Usage: remove <replica>")
					} else {
						client ! Remove(cmdArgs(1))
					}
				case "print" | "p" =>
					if (cmdArgs.size < 2) {
						println("[CONSOLE] Usage: "+cmdArgs(0)+" replicas/dht/statemachine/clientreplicas/test")
					} else {
						cmdArgs(1) match {
							case "replicas" | "r" =>
								multipaxos ! DebugPrintReplicas()
							case "dht" | "data" | "d" =>
								statemachine ! DebugPrintStore()
							case "statemachine" | "state" | "s" =>
								statemachine ! DebugPrintStateMachine()
							case "clientreplicas" | "cr" =>
								client ! DebugPrintClientReplicas()
							case "test" | "t" | "stats" =>
								client ! DebugPrintTestResults()
							case dunno =>
								println("[CONSOLE] Can't print "+dunno)
						}
					}
				case "name" | "n" =>
					if (cmdArgs.size < 2) {
						println("[CONSOLE] Usage: name <name>")
					} else {
						println("[CONSOLE] Hey "+cmdArgs.tail.mkString(" ")+"! :)")
					}
				case "test" | "t" =>
					if (cmdArgs.size < 2) {
						println("[CONSOLE] Usage: test <#ops>")
					} else {
						var numOps:Int = cmdArgs(1).toInt
						for(i <- 0 until numOps) {
							var pair:Int = i % 2
							if ( pair == 0 ) {
								client ! Write(i.toString, (System.currentTimeMillis).toString)
							} else {
								client ! Read((i - 1).toString)
							}
						}
					}
				case "help" | "h" =>
					println("[CONSOLE] Available commands:")
					println("> read")
					println("> write")
					println("> add")
					println("> remove")
					println("> test")
					println("> print")
					println("> help")
					println("> quit")
				case "exit" | "e" =>
					println("[CONSOLE] Exiting...")
				case "quit" | "q" =>
					println("[CONSOLE] Quitting...")
				case "close" | "c" =>
					println("[CONSOLE] Closing...")
				case _ =>
					println("[CONSOLE] Unknown command. Type 'help' for a list of commands")
			}
		} while (command != "exit" && command != "e" && command != "quit" && command != "q")

		try {
			actorSystem.terminate()
		} catch {
			case e:Throwable => println(e)
		}
	}
}
