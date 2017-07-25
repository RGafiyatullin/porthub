package com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_authenticating.authentication

import java.nio.charset.Charset

import akka.actor.Actor.Receive
import akka.actor.ActorRef
import akka.util.Timeout
import com.github.rgafiyatullin.porthub.socks5.pdu.AuthMethodUserPasswordPlainText
import com.github.rgafiyatullin.porthub.socks5.server.authentication_srv.AuthenticationSrv
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.ConnectionSrv
import com.github.rgafiyatullin.porthub.socks5.security.authentication.Identity
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.{ActorState, ActorStateTcpUtil}
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_authenticating.WhenAuthenticating.{Authenticator, AuthenticatorProvider}

import scala.concurrent.Future
import scala.util.Success

object UsernamePasswordAuthenticator {
  final case class Provider(state: ConnectionSrv.State) extends AuthenticatorProvider {
    override val authMethodId: Byte = 2

    override def create(onSuccess: Authenticator.OnSuccess): Authenticator =
        UsernamePasswordAuthenticator(state, onSuccess)
  }
}

final case class UsernamePasswordAuthenticator(
    state: ConnectionSrv.State,
    onSuccess: Authenticator.OnSuccess)
  extends Authenticator
    with ActorState[ConnectionSrv.ConnectionSrvActor]
    with ActorStateTcpUtil[ConnectionSrv.ConnectionSrvActor]
{
  val actor = state.actor
  val charset = Charset.forName("utf8")
  val stdReceive = actor.stdReceive

  val rqCodec = AuthMethodUserPasswordPlainText.encoding.authMethodUserPasswordPlainTextRqCodec
  val rsCodec = AuthMethodUserPasswordPlainText.encoding.authMethodUserPasswordPlainTextRsCodec

  override def receive(tcpUtilState: ActorStateTcpUtil.State): Receive =
    whenExpectingRq(tcpUtilState: ActorStateTcpUtil.State)

  def handleDecodeError(tcpUtilState: ActorStateTcpUtil.State, err: scodec.Err): Receive = {
    context stop self
    stdReceive.discard
  }

  def handlePDU(
    tcpUtilState: ActorStateTcpUtil.State,
    rq: AuthMethodUserPasswordPlainText.AuthMethodUserPasswordPlainTextRq)
  : Receive = {
    val username = new String(rq.uname.toArray, charset)
    val password = new String(rq.password.toArray, charset)

    actor.future.handle(checkUserPasswordPair(username, password)) {
      case Success(None) =>
        val response = AuthMethodUserPasswordPlainText.AuthMethodUserPasswordPlainTextRs(1)
        tcpUtil.write(state.downstreamTcp, rsCodec, response)
        context stop self
        stdReceive.discard

      case Success(Some(identity)) =>
        val response = AuthMethodUserPasswordPlainText.AuthMethodUserPasswordPlainTextRs(0)
        tcpUtil.write(state.downstreamTcp, rsCodec, response)
        onSuccess(state, identity, tcpUtilState)
    }
  }


  def checkUserPasswordPair(username: String, password: String): Future[Option[Identity]] =
    state.authenticationSrv.usernamePassword.authenticate(state.downstreamSocketAddress, username, password)(state.operationTimeout)



  def handleRq(tcpUtilState: ActorStateTcpUtil.State): Receive =
    tcpUtil.handleTcpReceived(state.downstreamTcp, rqCodec, tcpUtilState)(whenExpectingRq)(handlePDU)(handleDecodeError)

  def whenExpectingRq(tcpUtilState: ActorStateTcpUtil.State): Receive =
    handleRq(tcpUtilState) orElse
      stdReceive.discard
}
