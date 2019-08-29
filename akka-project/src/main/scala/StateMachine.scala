import akka.actor.Actor
import akka.actor.ActorRef
import scala.util.Random
import akka.actor.ActorSelection
import scala.collection.mutable.Queue
import scala.collection.mutable.Map

class StateMachine(myHostname:String, myPort:String) extends Actor {
	var myIpPort:String = myHostname+":"+myPort

	var leader:String = null

	var currentRound:Int = 0 // Current round

	var stateHistory:Array[Operation] = new Array[Operation](100000) // The actual state machine
	var nextToExecute:Int = 0 // Next operation to execute

	var operationsQueue:Queue[Operation] = new Queue[Operation]() // Queue of operations waiting to be decided

	var dht:Map[String, String] = Map[String, String]() // The key value store

	def receive = {
		case OrderOperation(op:Operation) =>
			println("[STATEMACHINE] OrderOperation")
			if (leader == null || (leader != null && leader == myIpPort)){
				println("[STATEMACHINE] There is no leader or I'm it")
				operationsQueue.enqueue(op) // Add to operations queue
				if (operationsQueue.size == 1){
					println("[STATEMACHINE] No op in front, proposing...")
					getActorRef(myIpPort,"multipaxos") ! Propose(currentRound, op)
				}
			} else {
				println("[STATEMACHINE] There's a leader that isn't me, forwarding to "+leader)
				getActorRef(leader, "statemachine") ! OrderOperation(op) // Forward op to leader
			}
		case Decided(round:Int, op:Operation) =>
			if (stateHistory(round) == null){
				println("[STATEMACHINE] Decided "+op+" for round "+round)

				stateHistory(round) = op // Add operation to state machine

				// Execute all operations from the point we last stopped until another "hole" in the sequence is found
				while (stateHistory(nextToExecute) != null){
					var opType:String = stateHistory(nextToExecute).opType
					opType match {
						case "read" =>
							println("[STATEMACHINE] Executing read value")
							var value:Option[String] = dht.get(stateHistory(nextToExecute).opArg1) // Read value
							if (value == None){
								getActorRef(myIpPort, "app") ! OperationResult(stateHistory(nextToExecute), "No entry for key \""+stateHistory(nextToExecute).opArg1+"\"") // Send reply to the app layer
							} else {
								getActorRef(myIpPort, "app") ! OperationResult(stateHistory(nextToExecute), "dht(\""+stateHistory(nextToExecute).opArg1+"\"): "+value.get) // Send reply to the app layer
							}
						case "write" =>
							println("[STATEMACHINE] Executing write value")
							var oldValue:Option[String] = dht.get(stateHistory(nextToExecute).opArg1) // Get the old value
							dht(stateHistory(nextToExecute).opArg1) = stateHistory(nextToExecute).opArg2 // Write the new value
							if (oldValue == None){
								getActorRef(myIpPort, "app") ! OperationResult(stateHistory(nextToExecute), "No entry for key \""+stateHistory(nextToExecute).opArg1+"\"") // Send reply to the app layer
							} else {
								getActorRef(myIpPort, "app") ! OperationResult(stateHistory(nextToExecute), "dht(\""+stateHistory(nextToExecute).opArg1+"\"): "+oldValue.get) // Send reply to the app layer
							}
						case "add" =>
							println("[STATEMACHINE] Executing add replica")
							getActorRef(myIpPort, "multipaxos") ! AddReplica(stateHistory(nextToExecute).opArg1)
							getActorRef(myIpPort, "app") ! OperationResult(stateHistory(nextToExecute), "OK") // Send reply to the app layer
						case "remove" =>
							println("[STATEMACHINE] Executing remove replica")
							getActorRef(myIpPort, "multipaxos") ! RemoveReplica(stateHistory(nextToExecute).opArg1)
							getActorRef(myIpPort, "app") ! OperationResult(stateHistory(nextToExecute), "OK") // Send reply to the app layer
						case whatevs =>
							println("[STATEMACHINE] Unknown state machine operation: "+whatevs)
					}

					nextToExecute = nextToExecute + 1 // Increment pointer to the next operation
				}

				if (round == currentRound){
					println("[STATEMACHINE] Decide was for the current round, advancing to round "+(currentRound+1))
					currentRound = currentRound + 1 // Increment round

					// Check queue
					if (operationsQueue.size > 0){
						if (operationsQueue.head.opId == op.opId){
							// Our operation was executed, dequeue it
							operationsQueue.dequeue()
							println("[STATEMACHINE] Dequeued my operation "+op.opId)

							if (operationsQueue.size > 0){
								// There's another operation waiting in the queue
								println("[STATEMACHINE] There's an operation waiting in the queue")

								if (leader == null || (leader != null && leader == myIpPort)){
									println("[STATEMACHINE] There is no leader or I'm it, proposing "+operationsQueue.head.opId+" again")
									getActorRef(myIpPort,"multipaxos") ! Propose(currentRound, operationsQueue.head)
								} else {
									println("[STATEMACHINE] Turns out I'm not the leader, but I have ops on my queue, forwarding the head ("+operationsQueue.head.opId+") to "+leader)
									getActorRef(leader, "statemachine") ! OrderOperation(operationsQueue.dequeue()) // Forward op at the head of my queue to the leader
								}
							}
						} else {
							// Our operation was not executed, propose it again
							println("[STATEMACHINE] "+op.opId+" was not executed this time")

							if (leader == null || (leader != null && leader == myIpPort)){
								println("[STATEMACHINE] There is no leader or I'm it, proposing "+operationsQueue.head.opId+" again")
								getActorRef(myIpPort,"multipaxos") ! Propose(currentRound, operationsQueue.head)
							} else {
								println("[STATEMACHINE] Turns out I'm not the leader, but I have ops on my queue, forwarding the head ("+operationsQueue.head.opId+") to "+leader)
								getActorRef(leader, "statemachine") ! OrderOperation(operationsQueue.dequeue()) // Forward op at the head of my queue to the leader
							}
						}
					} else {
						println("[STATEMACHINE] I didn't have anything on my operations queue, nothing to do")
					}
				}
			}
		case LeaderChange(leaderIpPort:Option[String]) =>
			if (leaderIpPort == None){
				println("[STATEMACHINE] Got a new leader from Paxos: "+leaderIpPort)
				leader = null // Update leader
			} else {
				println("[STATEMACHINE] Got a new leader from Paxos: "+leaderIpPort.get)
				leader = leaderIpPort.get // Update leader
			}
		case DebugPrintStore() =>
			println("[STATEMACHINE] List of key value pairs in the dht ("+dht.size+"):")
			for (pair <- dht){
				println("> "+pair)
			}
		case DebugPrintStateMachine() =>
			println("[STATEMACHINE] List of statemachine operations ("+currentRound+"):")
			for (i <- 0 until currentRound){
				println("> ("+i+") - "+stateHistory(i))
			}
		case whatevs =>
			println("[STATEMACHINE] Unknown message: "+whatevs)
	}
  
  	//Get actor's reference so we can send them a message.
	def getActorRef(actorIpPort:String, actorType:String) : ActorSelection = {
		return context.actorSelection("akka.tcp://ASDProject@"+actorIpPort+"/user/"+actorType) //akka.<protocol>://<actor system name>@<hostname>:<port>/<actor path>
	}
}
