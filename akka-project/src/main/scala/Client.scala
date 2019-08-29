import akka.actor.Actor
import akka.actor.ActorRef
import scala.util.Random
import akka.actor.ActorSelection
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Map
import akka.actor.Timers
import scala.concurrent.duration._

class Client(myHostname:String, myPort:String) extends Actor with Timers {

	var myIpPort:String = myHostname+":"+myPort

	var knownReplicas:Set[String] = Set() // Set of known replicas

	var opsToExecute:ListBuffer[Operation] = ListBuffer() // Queue of operations that the client is waiting for the reply

	var opStats:Map[String, Stats] = Map()

	def receive = {
		case Read(key:String) =>
			var start:Long = System.currentTimeMillis
			var op:Operation = Operation(myIpPort+":"+start.toString+"_"+Random.nextInt(9001), myIpPort, "read", key, null) // Create the read operation
			println("[CLIENT] Created operation read("+key+"): "+op.opId)
			sendOperation(op, start) // Send it
		case Write(key:String, value:String) =>
			var start:Long = System.currentTimeMillis
			var op:Operation = Operation(myIpPort+":"+start.toString+"_"+Random.nextInt(9001), myIpPort, "write", key, value) // Create the write operation
			println("[CLIENT] Created operation write("+key+", "+value+"): "+op.opId)
			sendOperation(op, start) // Send it
		case Add(replica:String) =>
			var start:Long = System.currentTimeMillis
			var op:Operation = Operation(myIpPort+":"+start.toString+"_"+Random.nextInt(9001), myIpPort, "add", replica, null) // Create the add operation
			println("[CLIENT] Created operation add("+replica+"): "+op.opId)
			sendOperation(op, start) // Send it
		case Remove(replica:String) =>
			var start:Long = System.currentTimeMillis
			var op:Operation = Operation(myIpPort+":"+start.toString+"_"+Random.nextInt(9001), myIpPort, "remove", replica, null) // Create the remove operation
			println("[CLIENT] Created operation remove("+replica+"): "+op.opId)
			sendOperation(op, start) // Send it
		case DeliverResult(op:Operation, result:String) =>
			opStats = opStats + ((op.opId, Stats(opStats(op.opId).startOnCreation, opStats(op.opId).startOnSend, System.currentTimeMillis)))
			opsToExecute -= op // Remove the operation from the queue of operations waiting for a reply
			println("[CLIENT] Got the result for "+op.opId+": "+result) // Print out the reply
			
			// Send the next operation in the queue if there's any
			if (opsToExecute.size > 0){
				var replicaIpPort:String = chooseReplica() // Choose a replica to send the operation
				println("[CLIENT] Sending "+opsToExecute.head.opId+" from queue to "+replicaIpPort)
				opStats = opStats + ((opsToExecute.head.opId, Stats(opStats(opsToExecute.head.opId).startOnCreation, System.currentTimeMillis, -1)))
				getActorRef(replicaIpPort, "app") ! ClientOp(opsToExecute.head) // Send operation to replica
				timers.startSingleTimer(opsToExecute.head.opId, OperationTimeout(replicaIpPort, opsToExecute.head), 10000.millis) // Start the reply timeout timer
			}
		case OperationTimeout(oldReplicaIpPort:String, op:Operation) =>
			// Resend operation if after timer expired we still have it in the queue of operations to execute
			if (opsToExecute.contains(op)){
				// Operation still wasn't executed
				var replicaIpPort:String = chooseReplica() // Choose another replica to send the operation
				println("[CLIENT] Timed out waiting for reply to "+op.opId+" from "+oldReplicaIpPort+", resending via "+replicaIpPort+"...")
				getActorRef(replicaIpPort, "app") ! ClientOp(op) // Send operation to replica
				timers.startSingleTimer(op.opId, OperationTimeout(replicaIpPort, op), 10000.millis) // Restart the reply timeout timer
			}
		case TriggerAskForReplicas() =>
			getActorRef(chooseReplica(), "multipaxos") ! AskForReplicas(myIpPort)
		case UpdateReplicas(replicas:Set[String]) =>
			knownReplicas = replicas
		case DebugPrintClientReplicas() =>
			println("[CLIENT] List of known replicas ("+knownReplicas.size+"):")
			for (replica <- knownReplicas){
				var replicaStr:String = "> " + replica
				if (replica == myIpPort){
					replicaStr = replicaStr + " (ME)"
				}
				println(replicaStr)
			}
		case DebugPrintTestResults() =>
			println("[CLIENT] Test results:")
			var opsCompletedCount:Int = 0
			var opsOnCreationLatencySum:Long = 0
			var opsOnSendLatencySum:Long = 0
			for (opStat <- opStats){
				if (opStat._2.end != -1){
					var opOnCreationLatency:Long = (opStat._2.end - opStat._2.startOnCreation)
					var opOnSendLatency:Long = (opStat._2.end - opStat._2.startOnSend)
					opsCompletedCount = opsCompletedCount + 1
					opsOnCreationLatencySum = opsOnCreationLatencySum + opOnCreationLatency
					opsOnSendLatencySum = opsOnSendLatencySum + opOnSendLatency
					println("> ("+opStat._1+") Start on Creation:"+opStat._2.startOnCreation+" | Start on Send:"+opStat._2.startOnSend+" | End:"+opStat._2.end+" | On Creation Latency: "+opOnCreationLatency+" ms"+" | On Send Latency: "+opOnSendLatency+" ms | Difference: "+(opOnCreationLatency - opOnSendLatency)+" ms")
				}
			}
			var avgOnCreationLatency:Long = opsOnCreationLatencySum / opsCompletedCount
			var avgOnSendLatency:Long = opsOnSendLatencySum / opsCompletedCount
			var throughput:Double = opsCompletedCount / (opsOnSendLatencySum / 1000.0)
			println("> (Total) Count: "+opsCompletedCount+" | Avg. On Creation Latency: "+avgOnCreationLatency+" ms | Avg. On Send Latency: "+avgOnSendLatency+" ms | Difference: "+(avgOnCreationLatency - avgOnSendLatency)+" ms | Throughput: "+throughput+" ops/s")
		case whatevs =>
			println("[CLIENT] Unknown message: "+whatevs)
	}

	def getActorRef(actorIpPort:String, actorType:String) : ActorSelection = {
		return context.actorSelection("akka.tcp://ASDProject@"+actorIpPort+"/user/"+actorType) //akka.<protocol>://<actor system name>@<hostname>:<port>/<actor path>
	}

	// Get the current timestamp
	def getCurrentTimestamp() : String = {
		return (System.currentTimeMillis).toString
	}

	// Choose a random replica to send the messages to
	def chooseReplica() : String = {
		return knownReplicas.drop(Random.nextInt(knownReplicas.size)).head // Choose a random replica
	}

	// Enqueue the operation to send to a replica and send it if there's nothing in front
	def sendOperation(op:Operation, start:Long) = {
		// Add operation to the set of operations waiting to complete
		opsToExecute += op

		// Save operation and the timestamp when it was requested
		opStats = opStats + ((op.opId, Stats(start, -1, -1)))
		
		// Send the operation immediately if there isn't another in front of it
		if (opsToExecute.size == 1){
			var replicaIpPort:String = chooseReplica() // Choose a replica to send the operation
			println("[CLIENT] Sending "+op.opId+" to "+replicaIpPort)
			opStats = opStats + ((op.opId, Stats(opStats(op.opId).startOnCreation, System.currentTimeMillis, -1)))
			getActorRef(replicaIpPort, "app") ! ClientOp(op) // Send operation to replica
			timers.startSingleTimer(op.opId, OperationTimeout(replicaIpPort, op), 10000.millis) // Start the reply timeout timer
		}
	}

}
