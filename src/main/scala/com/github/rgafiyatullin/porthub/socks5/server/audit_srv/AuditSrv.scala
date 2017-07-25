package com.github.rgafiyatullin.porthub.socks5.server.audit_srv

import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, Props}
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.ActorStdReceive
import com.github.rgafiyatullin.porthub.socks5.security.audit.Event
import com.github.rgafiyatullin.porthub.socks5.server.Config

object AuditSrv {
  def create(args: Args)(implicit arf: ActorRefFactory): AuditSrv =
    AuditSrv(arf.actorOf(Props(classOf[AuditSrvActor], args), "audit-srv"))

  final case class Args(config: Config.Audit)


  object api {
    final case class ReportEvent(e: Event)
  }


  final case class State()

  final class AuditSrvActor(args: Args)
    extends Actor
      with ActorStdReceive
      with ActorLogging
  {
    override def receive = initialize()


    def handleEvent(state: AuditSrv.State): Receive = {
      case api.ReportEvent(event) =>
        log.warning("ReportEvent:::{}", event)
    }

    def whenReady(state: State): Receive =
      handleEvent(state) orElse
        stdReceive.discard

    def initialize(): Receive =
      whenReady(State())
  }
}

final case class AuditSrv(actorRef: ActorRef) {
  def report(event: Event): Unit =
    actorRef ! AuditSrv.api.ReportEvent(event)
}
