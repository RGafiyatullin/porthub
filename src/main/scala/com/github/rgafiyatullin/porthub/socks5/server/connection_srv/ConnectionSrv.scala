package com.github.rgafiyatullin.porthub.socks5.server.connection_srv

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, Props}
import akka.util.Timeout
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.{ActorFuture, ActorStdReceive}
import com.github.rgafiyatullin.porthub.socks5.security.audit.Event
import com.github.rgafiyatullin.porthub.socks5.server.audit_srv.AuditSrv
import com.github.rgafiyatullin.porthub.socks5.server.authentication_srv.AuthenticationSrv
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_authenticating.WhenAuthenticating
import org.joda.time.DateTime

object ConnectionSrv {
  final case class Args(
    tcpConnection: ActorRef,
    remoteAddress: InetSocketAddress,
    authenticationSrv: AuthenticationSrv,
    auditSrv: AuditSrv,
    operationTimeout: Timeout)

  def create(args: Args)(implicit arf: ActorRefFactory): ConnectionSrv =
    ConnectionSrv(arf.actorOf(Props(classOf[ConnectionSrvActor], args)))

  object api {
    object auditEvents {
      final case class ClientConnected(
        downstreamSocketAddr: InetSocketAddress,
        connectionActorRef: ActorRef,
        at: DateTime = DateTime.now())
          extends Event.WithDownstreamSocketAddr
            with Event.WithConnection
      {
        override def toString =
          s"[$at] New connection [via: $connectionActorRef; from: $downstreamSocketAddr]"
      }
    }

    case object TryDecodingInputBuffer
  }

  final case class DecodeError(scodecErr: scodec.Err) extends Exception

  final case class State(
    actor: ConnectionSrvActor,
    downstreamTcp: ActorRef,
    downstreamSocketAddress: InetSocketAddress,
    authenticationSrv: AuthenticationSrv,
    auditSrv: AuditSrv,
    operationTimeout: Timeout)

  class ConnectionSrvActor(args: Args)
    extends Actor
      with ActorStdReceive
      with ActorFuture
      with ActorLogging
  {
    override def receive = {
      args.auditSrv.report(
        api.auditEvents.ClientConnected(args.remoteAddress, self))

      WhenAuthenticating(
        State(
          this,
          args.tcpConnection,
          args.remoteAddress,
          args.authenticationSrv,
          args.auditSrv,
          args.operationTimeout)).receive()
    }
  }
}

final case class ConnectionSrv(actorRef: ActorRef) {

}
