import akka.actor.ActorRef

// MULTI-PAXOS
case class Propose(round:Int, op:Operation)
case class Prepare(replyIpPort:String, round:Int, n:SequenceNumber)
case class PrepareOk(round:Int, sna:SequenceNumber, va:Operation)
case class Accept(replyIpPort:String, round:Int, n:SequenceNumber, v:Operation)
case class AcceptOk(round:Int, n:SequenceNumber, v:Operation)
case class AddReplica(replicaIpPort:String)
case class RemoveReplica(replicaIpPort:String)
case class TriggerPingLeader()
case class PingLeader(replicaIpPort:String)
case class PingLeaderAck()
case class PrepareMajorityTimeout(round:Int, sn:SequenceNumber, op:Operation)
case class AcceptMajorityTimeout(round:Int, sn:SequenceNumber, op:Operation)
case class AskForReplicas(replyIpPort:String)
case class DebugPrintReplicas()

// STATE MACHINE
case class OrderOperation(op:Operation)
case class Decided(round:Int, op:Operation)
case class LeaderChange(leaderIpPort:Option[String])
case class DebugPrintStore()
case class DebugPrintStateMachine()

// APP
case class ClientOp(op:Operation)
case class OperationResult(op: Operation, result: String)

// CLIENT
case class Read(key:String)
case class Write(key:String, value:String)
case class Add(replica:String)
case class Remove(replica:String)
case class DeliverResult(op:Operation, result:String)
case class OperationTimeout(oldReplicaIpPort:String, op:Operation)
case class TriggerAskForReplicas()
case class UpdateReplicas(replicas:Set[String])
case class DebugPrintClientReplicas()
case class DebugPrintTestResults()
