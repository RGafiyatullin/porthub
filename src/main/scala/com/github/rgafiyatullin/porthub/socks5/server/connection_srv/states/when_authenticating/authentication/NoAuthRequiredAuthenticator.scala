package com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_authenticating.authentication

import akka.actor.Actor.Receive
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.ConnectionSrv
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.{ActorState, ActorStateTcpUtil}
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_authenticating.WhenAuthenticating.{Authenticator, AuthenticatorProvider}

import scala.util.Success

object NoAuthRequiredAuthenticator {
  final case class Provider(state: ConnectionSrv.State) extends AuthenticatorProvider {
    override val authMethodId: Byte = 0

    override def create(onSuccess: Authenticator.OnSuccess): Authenticator =
        NoAuthRequiredAuthenticator(state, onSuccess)
  }
}

final case class NoAuthRequiredAuthenticator(
    state: ConnectionSrv.State,
    onSuccess: Authenticator.OnSuccess)
  extends Authenticator
    with ActorState[ConnectionSrv.ConnectionSrvActor]
    with ActorStateTcpUtil[ConnectionSrv.ConnectionSrvActor]
{
  val actor = state.actor
  val stdReceive = actor.stdReceive
  val log = actor.log

  override def receive(tcpUtilState: ActorStateTcpUtil.State): Receive =
    actor.future.handle(state.authenticationSrv.anonymous.authenticate(state.downstreamSocketAddress)(state.operationTimeout)) {
      case Success(Some(identity)) =>
        onSuccess(state, identity, tcpUtilState)

      case Success(None) =>
        context stop self
        stdReceive.discard
    }

}
