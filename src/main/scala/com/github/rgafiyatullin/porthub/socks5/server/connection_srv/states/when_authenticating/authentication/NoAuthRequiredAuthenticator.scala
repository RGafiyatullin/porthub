package com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_authenticating.authentication

import akka.actor.Actor.Receive
import akka.actor.ActorRef
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.ConnectionSrv
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.{ActorState, ActorStateTcpUtil}
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_authenticating.WhenAuthenticating.{Authenticator, AuthenticatorProvider}

object NoAuthRequiredAuthenticator {
  object Provider extends AuthenticatorProvider {
    override val authMethodId: Byte = 0

    override def create(
      actor: ConnectionSrv.ConnectionSrvActor,
      downstreamTcp: ActorRef,
      onSuccess: Authenticator.OnSuccess): Authenticator =
        NoAuthRequiredAuthenticator(actor, downstreamTcp, onSuccess)
  }
}

final case class NoAuthRequiredAuthenticator(
    actor: ConnectionSrv.ConnectionSrvActor,
    downstreamTcp: ActorRef,
    onSuccess: Authenticator.OnSuccess)
  extends Authenticator
    with ActorState[ConnectionSrv.ConnectionSrvActor]
    with ActorStateTcpUtil[ConnectionSrv.ConnectionSrvActor]
{
  override def receive(tcpUtilState: ActorStateTcpUtil.State): Receive =
    onSuccess(actor, downstreamTcp, tcpUtilState)
}
