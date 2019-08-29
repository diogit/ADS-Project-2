import akka.actor.Actor
import akka.actor.ActorRef
import scala.util.Random
import akka.actor.ActorSelection
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Map

class App(myHostname:String, myPort:String) extends Actor {

	var myIpPort:String = myHostname+":"+myPort

  	var askedOperations:ListBuffer[Operation] = new ListBuffer() // All the operations asked by the clients to this replica

	var results:Map[String, String] = Map() // Map of the answers for all operations executed in the system. Key is the Operation id and Value is the reply sent to the client

	def receive = {
		case ClientOp(op:Operation) =>
			println("[APP] Got "+op.opId+" which is a "+op.opType+" operation")

			var res = results.get(op.opId) // Check if we already answered this operation previously, in case the user didn't get the reply

			// If there isn't a reply yet, send the request to the layer below (state machine), otherwise resend the reply to the client
			if (res == None) {
				askedOperations += op // Add the new operation to the list of requested operations
				println("[APP] Asking to order "+op.opId)
				getActorRef(myIpPort,"statemachine") ! OrderOperation(op) // Ask the state machine to order the operation
			} else {
				println("[APP] Already executed "+op.opId+", sending previously computed answer")
				getActorRef(op.clientIpPort, "client") ! DeliverResult(op, res.get) // Deliver the previously computed answer
			}
		case OperationResult(op:Operation, result:String) =>
			println("[APP] Storing the answer for "+op.opId+": "+result)
			results += (op.opId -> result) // Add the new reply to the map of results
      		// If the client sent the request to this replica, reply to it
			if (askedOperations.contains(op)) {
				askedOperations -= op // Remove it from the list of requested operations
				println("[APP] Sending answer to client")
				getActorRef(op.clientIpPort, "client") ! DeliverResult(op, result) // Send the result of the operation to the client
			}
		case whatevs =>
			println("[APP] Unknown message: "+whatevs)
	}

	def getActorRef(actorIpPort:String, actorType:String) : ActorSelection = {
		return context.actorSelection("akka.tcp://ASDProject@"+actorIpPort+"/user/"+actorType) //akka.<protocol>://<actor system name>@<hostname>:<port>/<actor path>
	}

}
