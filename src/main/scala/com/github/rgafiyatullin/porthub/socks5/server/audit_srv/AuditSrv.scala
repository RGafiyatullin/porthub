package com.github.rgafiyatullin.porthub.socks5.server.audit_srv

import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, Props, Terminated}
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.ActorStdReceive
import com.github.rgafiyatullin.porthub.socks5.security.audit.Event
import com.github.rgafiyatullin.porthub.socks5.server.Config
import org.joda.time.DateTime

import scala.collection.immutable.Queue

object AuditSrv {
  def create(args: Args)(implicit arf: ActorRefFactory): AuditSrv =
    AuditSrv(arf.actorOf(Props(classOf[AuditSrvActor], args), "audit-srv"))

  final case class Args(config: Config.Audit)


  object api {
    final case class ReportEvent(e: Event)
  }
  object auditEvents {
    final case class ConnectionDown(
      connectionActorRef: ActorRef,
      at: DateTime = DateTime.now()) extends Event.WithConnection
    {
      override def toString =
        s"[$at] Connection down [via: $connectionActorRef]"
    }
  }


  final case class State(
    perConnection: Map[ActorRef, Queue[Event.WithConnection]] = Map.empty)
  {
    def isKnownAsConnection(actorRef: ActorRef): Boolean =
      perConnection.contains(actorRef)

    def reportPerConnectionEvent(event: Event.WithConnection): (Boolean, Option[Seq[Event.WithConnection]], State) = {
      event match {
        case connDown @ auditEvents.ConnectionDown(connectionActorRef, _) =>
          (false,
            Some(perConnection.getOrElse(connectionActorRef, Queue.empty).enqueue(connDown)),
            copy(perConnection = perConnection - connectionActorRef))

        case _ =>
          val actorRef = event.connectionActorRef
          val q0 = perConnection.getOrElse(actorRef, Queue.empty)
          val q1 = q0.enqueue(event)
          (q0.isEmpty, None, copy(perConnection = perConnection + (actorRef -> q1)))
      }
    }

  }

  final class AuditSrvActor(args: Args)
    extends Actor
      with ActorStdReceive
      with ActorLogging
  {
    override def receive = initialize()

    def processAuditEventWithConnection(event: Event, state: State): State =
      event match {
        case eventWithConnection: Event.WithConnection =>
          val (shouldWatch, eventsToFlushOption, stateNext) = state.reportPerConnectionEvent(eventWithConnection)

          if (shouldWatch)
            context watch eventWithConnection.connectionActorRef

//          eventsToFlushOption.foreach { eventsToFlush =>
//            log.info("BEGIN FLUSH CONNECTION EVENTS {}", eventWithConnection.connectionActorRef)
//            eventsToFlush.foreach(log.info("||| {}", _))
//            log.info("END   FLUSH CONNECTION EVENTS {}", eventWithConnection.connectionActorRef)
//          }

          stateNext

        case _ =>
          state
      }

    def processAuditEvent(event: Event, state0: State): State = {
      log.info("{}", event)
      val state1 = processAuditEventWithConnection(event, state0)

      state1
    }

    def handleEvent(state: State): Receive = {
      case api.ReportEvent(event) =>
        val stateNext = processAuditEvent(event, state)
        context become whenReady(stateNext)
    }

    def handleActorTerminated(state: State): Receive = {
      case Terminated(deadActorRef) =>
        val stateNext =
          if (state.isKnownAsConnection(deadActorRef))
            processAuditEvent(auditEvents.ConnectionDown(deadActorRef), state)
          else
            state

        context become whenReady(stateNext)
    }

    def whenReady(state: State): Receive =
      handleEvent(state) orElse
        handleActorTerminated(state) orElse
        stdReceive.discard

    def initialize(): Receive =
      whenReady(State())
  }
}

final case class AuditSrv(actorRef: ActorRef) {
  def report(event: Event): Unit =
    actorRef ! AuditSrv.api.ReportEvent(event)
}
