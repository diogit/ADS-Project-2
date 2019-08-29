import akka.actor.Actor
import akka.actor.ActorRef
import scala.util.Random
import scala.collection.mutable.ListBuffer
import akka.actor.ActorSelection
import akka.actor.Timers
import scala.concurrent.duration._

class MultiPaxos(myPaxosId:Int, myHostname:String, myPort:String) extends Actor with Timers {
	var replicas:Set[String] = Set() // Set of replicas

	var myIpPort:String = myHostname+":"+myPort
	var leader:String = null // Leader Ip:Port if there is one, null otherwise

	var currentRound:Int = 0 // Current known round

	var proposerSeqNum:SequenceNumber = SequenceNumber(myPaxosId, 0) // Proposer current sequence number
	var proposerValue:Operation = Operation(null, null, null, null, null) // Proposer current value
	var proposerPrepareOkReceived:ListBuffer[(SequenceNumber, Operation)] = ListBuffer() // PrepareOk received by the proposer

	var acceptorHighestPrepareSeqNum:SequenceNumber = SequenceNumber(myPaxosId, 0) // Acceptor highest prepare sequence number
	var acceptorHighestAcceptSeqNum:SequenceNumber = SequenceNumber(myPaxosId, 0) // Acceptor highest accept sequence number
	var acceptorHighestAcceptValue:Operation = Operation(null, null, null, null, null) // Acceptor highest accept value

	var learnerHighestAcceptSeqNum:SequenceNumber = SequenceNumber(myPaxosId, 0) // Learner highest accept sequence number
	var learnerHighestAcceptValue:Operation = Operation(null, null, null, null, null) // Learner highest accept value
	var learnerAcceptOkCount:Int = 0 // Number of AcceptOk received by the learner

	var leaderPingCounter:Int = 0 // Number of pings sent to leader since last response
	val leaderPingLimit:Int = 3 // Limit of pings before assuming the leader died

	def receive = {
		case Propose(round:Int, op:Operation) =>
			if (round < currentRound){
				//println("[MULTIPAXOS] Got a proposal "+op+" for round "+round+" which is previous to current known round "+currentRound+", ignoring it...")
			} else {
				println("[MULTIPAXOS] [PROPOSER] Proposing for round "+round+": "+op)
				currentRound = round //Update the round number
				proposerPrepareOkReceived = ListBuffer() // Reset the list of received PrepareOk
				if (leader == null){
					// I don't have a leader
					println("[MULTIPAXOS] [PROPOSER] Don't have a leader")
					proposerSeqNum = getNextSequenceNumber(proposerSeqNum) // Get the next sequence number
					proposerValue = op
					/// Start election
					for (replica <- replicas){
						println("[MULTIPAXOS] [PROPOSER] Sending Prepare to "+replica)
						getActorRef(replica, "multipaxos") ! Prepare(myIpPort, currentRound, proposerSeqNum)
					}
					timers.startSingleTimer(op.opId, PrepareMajorityTimeout(currentRound, proposerSeqNum, op), 5000.millis) // Start the majority timeout timer
				} else if (leader == myIpPort){
					// I am the leader
					println("[MULTIPAXOS] [PROPOSER] I am the leader")
					proposerSeqNum = getNextSequenceNumber(proposerSeqNum) // Get the next sequence number
					proposerValue = op
					for (replica <- replicas){
						println("[MULTIPAXOS] [PROPOSER] Sending Accept("+proposerValue+") to "+replica)
						getActorRef(replica, "multipaxos") ! Accept(myIpPort, currentRound, proposerSeqNum, proposerValue)
					}
					timers.startSingleTimer(proposerValue.opId, AcceptMajorityTimeout(currentRound, proposerSeqNum, proposerValue), 5000.millis) // Start the majority timeout timer
				} else {
					// Someone else is the leader, how did I get this?
					println("[MULTIPAXOS] [PROPOSER] [ERROR] Yo, I ain't the leader, that's "+leader+"! Who fell asleep at their post and sent me this?")
				}
			}
		case Prepare(replyIpPort:String, round:Int, n:SequenceNumber) =>
			println("[MULTIPAXOS] [ACCEPTOR] Prepare")
			if (round > currentRound){
				// Apparently we're on a new round now
				println("[MULTIPAXOS] [ACCEPTOR] Moving from round "+currentRound+" to "+round)
				currentRound = round
			}
			if (round == currentRound){
				// It's a Prepare for the current round
				if (isSeqNumGreaterThan(n, acceptorHighestPrepareSeqNum)){
					// Prepare sequence number is the highest so far
					acceptorHighestPrepareSeqNum = n
					println("[MULTIPAXOS] [ACCEPTOR] New highest prepare "+n+" for round "+currentRound+". Choosing "+replyIpPort+" as my new leader")
					leader = replyIpPort // Choosing sender as the new leader
					getActorRef(myIpPort, "statemachine") ! LeaderChange(Some(leader)) // Report change to the state machine layer
					leaderPingCounter = 0 // Reset the ping counter
					getActorRef(replyIpPort, "multipaxos") ! PrepareOk(currentRound, acceptorHighestAcceptSeqNum, acceptorHighestAcceptValue)
				} else {
					//println("[MULTIPAXOS] [ACCEPTOR] !isSeqNumGreaterThan("+n+", "+acceptorHighestPrepareSeqNum+")")
				}
			} else {
				// Not a Prepare for the current round
				//println("[MULTIPAXOS] [ACCEPTOR] [ERROR] Not a Prepare for the current round: "+round+"!="+currentRound)
			}
		case PrepareOk(round:Int, sna:SequenceNumber, va:Operation) =>
			if (round == currentRound){
				println("[MULTIPAXOS] [PROPOSER] Received PrepareOk from a replica")
				proposerPrepareOkReceived.append((sna, va)) // Store received PrepareOk
				if (isMajority(proposerPrepareOkReceived.size)){
					println("[MULTIPAXOS] [PROPOSER] PrepareOk majority reached")
					var maxVA:Operation = maxValue(proposerPrepareOkReceived) // Get the value associated with the highest sequence number or null if there's none
					if (maxVA.opId == null){
						maxVA = proposerValue // Nothing accepted yet, using ours
					}
					proposerPrepareOkReceived = ListBuffer() // Reset list of received PrepareOk
					// Ask to accept value
					for (replica <- replicas){
						println("[MULTIPAXOS] [PROPOSER] Sending Accept("+maxVA+") to "+replica)
						getActorRef(replica, "multipaxos") ! Accept(myIpPort, currentRound, proposerSeqNum, maxVA)
					}
					timers.startSingleTimer(maxVA.opId, AcceptMajorityTimeout(currentRound, proposerSeqNum, maxVA), 5000.millis) // Start the majority timeout timer
				} else {
					//println("[MULTIPAXOS] [PROPOSER] PrepareOk majority not reached yet. Only "+proposerPrepareOkReceived.size+" PrepareOk.")
					//println("[MULTIPAXOS] [PROPOSER] PREPAREOK DUMP: "+proposerPrepareOkReceived)
				}
			} else {
				// Not a PrepareOk for the current round
				//println("[MULTIPAXOS] [PROPOSER] [ERROR] Not a PrepareOk for the current round: "+round+"!="+currentRound)
			}
		case Accept(replyIpPort:String, round:Int, n:SequenceNumber, v:Operation) =>
			println("[MULTIPAXOS] [ACCEPTOR] Accept")
			if (round > currentRound){
				// Apparently we're on a new round now 
				println("[MULTIPAXOS] [ACCEPTOR] Moving from round "+currentRound+" to "+round)
				currentRound = round
			}
			if (round == currentRound){
				// It's an Accept for the current round
				if (isSeqNumGreaterThanOrEqual(n, acceptorHighestPrepareSeqNum)){
					// Prepare sequence number is the highest so far
					acceptorHighestAcceptSeqNum = n
					acceptorHighestAcceptValue = v
					println("[MULTIPAXOS] [ACCEPTOR] Accepting "+v+" ("+n+") for round "+currentRound)
					if (replyIpPort != leader){
						println("[MULTIPAXOS] [ACCEPTOR] Choosing "+replyIpPort+" instead as my new leader instead of "+leader)
						leader = replyIpPort // Choosing sender as the new leader
						getActorRef(myIpPort, "statemachine") ! LeaderChange(Some(leader)) // Report change to the state machine layer
						leaderPingCounter = 0 // Reset the ping counter
					}
					for (replica <- replicas){
						getActorRef(replica, "multipaxos") ! AcceptOk(currentRound, acceptorHighestAcceptSeqNum, acceptorHighestAcceptValue)
					}
				} else {
					//println("[MULTIPAXOS] [ACCEPTOR] !isSeqNumGreaterThanOrEqual("+n+", "+acceptorHighestPrepareSeqNum+")")
				}
			} else {
				// Not an Accept for the current round
				//println("[MULTIPAXOS] [ACCEPTOR] [ERROR] Not an Accept for the current round: "+round+"!="+currentRound)
			}
		case AcceptOk(round:Int, n:SequenceNumber, v:Operation) =>
			if (round > currentRound) {
				// Apparently we're on a new round now
				println("[MULTIPAXOS] [LEARNER] Moving from round "+currentRound+" to "+round)
				currentRound = round
			}
			if (round == currentRound) {
				println("[MULTIPAXOS] [LEARNER] Received AcceptOk from a replica")
				if (isSeqNumGreaterThanOrEqual(n, learnerHighestAcceptSeqNum)){
					if (isSeqNumGreaterThan(n, learnerHighestAcceptSeqNum)){
						println("[MULTIPAXOS] [LEARNER] Learnt about an higher n, replacing value to accept...")
						learnerHighestAcceptSeqNum = n
						learnerHighestAcceptValue = v
						learnerAcceptOkCount = 0
					}
					// n is the same as learnerHighestAcceptSeqNum
					learnerAcceptOkCount = learnerAcceptOkCount + 1 // Increment AcceptOk counter

					if (isMajority(learnerAcceptOkCount)){
						learnerAcceptOkCount = 0
						println("[MULTIPAXOS] [LEARNER] AcceptOk majority reached")
						getActorRef(myIpPort, "statemachine") ! Decided(currentRound, learnerHighestAcceptValue)
						currentRound = currentRound + 1 // Assuming we move on to the next round
					} else {
						println("[MULTIPAXOS] [PROPOSER] AcceptOk majority not reached yet. Only "+learnerAcceptOkCount+" AcceptOk.")
					}
				} else {
					//println("[MULTIPAXOS] [LEARNER] !isSeqNumGreaterThanOrEqual("+n+", "+learnerHighestAcceptSeqNum+")")
				}
			} else {
				// Not an AcceptOk for the current round
				//println("[MULTIPAXOS] [LEARNER] [ERROR] Not an AcceptOk for the current round: "+round+"!="+currentRound)
			}
		case AddReplica(replicaIpPort:String) =>
			println("[MULTIPAXOS] AddReplica: "+replicaIpPort)
			replicas = replicas + replicaIpPort
		case RemoveReplica(replicaIpPort:String) =>
			println("[MULTIPAXOS] RemoveReplica: "+replicaIpPort)
			replicas = replicas - replicaIpPort
		case TriggerPingLeader() =>
			if (leader != null) {
				// There is a leader (or at least there was one)
				if (leaderPingCounter >= leaderPingLimit){
					// Yep, that fellar sure's dead
					println("[MULTIPAXOS] Haven't heard of leader "+leader+" after "+leaderPingCounter+" pings. Assuming it's dead...")
					var oldLeader:String = leader
					leader = null // Remove the dead leader
					getActorRef(myIpPort, "statemachine") ! LeaderChange(None) // Report change to the state machine layer
					leaderPingCounter = 0 // Reset the ping counter
					getActorRef(myIpPort, "multipaxos") ! Propose(currentRound, Operation(myIpPort+":"+getCurrentTimestamp()+"_PROPOSER_GENERATED", myIpPort, "remove", oldLeader, null)) // Propose removal of the leader replica
				} else {
					// The leader is still alive, I think...
					getActorRef(leader, "multipaxos") ! PingLeader(myIpPort) // Ping leader
					leaderPingCounter = leaderPingCounter + 1 // Increment ping counter
				}
			} else {
				// Do you expect me to ping a ghost or something? Git on outta here! https://youtu.be/BT0z1FlGjMI?t=11
			}
		case PingLeader(replicaIpPort:String) =>
			getActorRef(replicaIpPort, "multipaxos") ! PingLeaderAck() // Acknowledge the ping request
		case PingLeaderAck() =>
			//println("[MULTIPAXOS] Our dear leader remains alive and strong. Long live the leader!")
			leaderPingCounter = 0 // Reset the ping counter
		case PrepareMajorityTimeout(round:Int, sn:SequenceNumber, op:Operation) =>
			//println("[MULTIPAXOS] [PROPOSER] Timed out waiting on a PrepareOk majority for "+op.opId+" on round "+round)
			getActorRef(myIpPort, "multipaxos") ! Propose(round, op) // Resubmit proposal
		case AcceptMajorityTimeout(round:Int, sn:SequenceNumber, op:Operation) =>
			//println("[MULTIPAXOS] [PROPOSER] Timed out waiting on a AcceptOk majority for "+op.opId+" on round "+round)
			getActorRef(myIpPort, "multipaxos") ! Propose(round, op) // Resubmit proposal
		case AskForReplicas(replyIpPort:String) =>
			getActorRef(replyIpPort, "client") ! UpdateReplicas(replicas) // Send our current set of known replicas to the client
		case DebugPrintReplicas() =>
			println("[MULTIPAXOS] List of replicas ("+replicas.size+"):")
			for (replica <- replicas){
				var replicaStr:String = "> " + replica
				if (replica == myIpPort){
					replicaStr = replicaStr + " (ME)"
				}
				if (replica == leader){
					replicaStr = replicaStr + " (LEADER)"
				}
				println(replicaStr)
			}
		case whatevs =>
			println("[MULTIPAXOS] Unknown message: "+whatevs)
	}

	// Get the next sequence number
	def getNextSequenceNumber(seqNum:SequenceNumber) : SequenceNumber = {
		return SequenceNumber(seqNum.processId, seqNum.counter + 1)
	}

	//Get actor's reference so we can send them a message.
	def getActorRef(actorIpPort:String, actorType:String) : ActorSelection = {
		return context.actorSelection("akka.tcp://ASDProject@"+actorIpPort+"/user/"+actorType) //akka.<protocol>://<actor system name>@<hostname>:<port>/<actor path>
	}

	// Check if there's a majority quorum
	def isMajority(count:Int) : Boolean = {
		return count >= (replicas.size / 2.0)
	}

	// Check if sequence number a is greater than sequence number b
	def isSeqNumGreaterThan(a:SequenceNumber, b:SequenceNumber) : Boolean = {
		return ((a.counter > b.counter) || (a.counter == b.counter && a.processId > b.processId))
	}

	// Check if sequence number a is greater than or equal sequence number b
	def isSeqNumGreaterThanOrEqual(a:SequenceNumber, b:SequenceNumber) : Boolean = {
		return ((a.counter > b.counter) || (a.counter == b.counter && a.processId > b.processId) || (a.counter == b.counter && a.processId == b.processId))
	}

	// Get the value associated with the highest sequence number or null if there's none
	def maxValue(s:ListBuffer[(SequenceNumber, Operation)]) : Operation = {
		var sn:SequenceNumber = SequenceNumber(0, 0)
		var v:Operation = Operation(null, null, null, null, null)

		for (pair <- s){
			if (isSeqNumGreaterThan(pair._1, sn)){
				// This one has an higher sequence number than previous ones
				sn = pair._1
				v = pair._2
			}
		}

		return v
	}

	// Get the current timestamp
	def getCurrentTimestamp() : String = {
		return (System.currentTimeMillis).toString
	}

}
