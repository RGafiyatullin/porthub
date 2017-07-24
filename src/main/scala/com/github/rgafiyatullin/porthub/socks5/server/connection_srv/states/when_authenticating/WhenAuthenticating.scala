package com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_authenticating

import akka.actor.Actor.Receive
import akka.actor.ActorRef
import akka.io.Tcp
import akka.util.Timeout
import com.github.rgafiyatullin.porthub.socks5.pdu.AuthMethodSelection
import com.github.rgafiyatullin.porthub.socks5.pdu.AuthMethodSelection.AuthMethodSelectionRs
import com.github.rgafiyatullin.porthub.socks5.server.authentication_srv.AuthenticationSrv
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.ConnectionSrv
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_authenticating.authentication.{NoAuthRequiredAuthenticator, UsernamePasswordAuthenticator}
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_selecting_mode.WhenSelectingMode
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.{ActorState, ActorStateTcpUtil}

object WhenAuthenticating {
  trait AuthenticatorProvider {
    val authMethodId: Byte

    def create(
      actor: ConnectionSrv.ConnectionSrvActor,
      downstreamTcp: ActorRef,
      onSuccess: Authenticator.OnSuccess): Authenticator
  }
  object Authenticator {
    type OnSuccess = (ConnectionSrv.ConnectionSrvActor, ActorRef, ActorStateTcpUtil.State) => Receive
  }

  trait Authenticator {
    def receive(tcpUtilState: ActorStateTcpUtil.State): Receive
  }
}

final case class WhenAuthenticating(
    actor: ConnectionSrv.ConnectionSrvActor,
    downstreamTcp: ActorRef,
    authenticationSrv: AuthenticationSrv,
    operationTimeout: Timeout)
  extends ActorState[ConnectionSrv.ConnectionSrvActor]
    with ActorStateTcpUtil[ConnectionSrv.ConnectionSrvActor]
{
  val stdReceive = actor.stdReceive
  val log = actor.log

  val authMethodSelectionRqCodec = AuthMethodSelection.encoding.authMethodSelectionRqCodec
  val authMethodSelectionRsCodec = AuthMethodSelection.encoding.authMethodSelectionRsCodec

  val authenticatorProviders =
    Seq(
      NoAuthRequiredAuthenticator.Provider,
      UsernamePasswordAuthenticator.Provider(authenticationSrv, operationTimeout)
    )
      .sortBy(_.authMethodId)
      .reverse
      .map(a => a.authMethodId -> a)
      .toMap

  def chooseAuthMethod(rq: AuthMethodSelection.AuthMethodSelectionRq): Byte = {
    val availableAuthIds = authenticatorProviders.keys.toSet
    val preferredAuthIds = rq.methods.toSet
    val commonIds = availableAuthIds intersect preferredAuthIds
    log.debug(
      "Choosing auth method. Server: [{}], Client: [{}], Intersect: [{}]",
      availableAuthIds.mkString(","), preferredAuthIds.mkString(","), commonIds.mkString(","))

    if (commonIds.isEmpty)
      255.toByte
    else
      commonIds.max
  }

  def handleRq(tcpUtilState: ActorStateTcpUtil.State, rq: AuthMethodSelection.AuthMethodSelectionRq): Receive = {
    log.debug("Client talks SOCKSv{} and supports the following auth-methods: [{}]", rq.version, rq.methods.mkString(","))
    val authMethodIdChosen = chooseAuthMethod(rq)
    log.debug("Auth method chosen: {}", authMethodIdChosen)
    val response = AuthMethodSelectionRs(authMethodIdChosen)
    tcpUtil.write(downstreamTcp, authMethodSelectionRsCodec, response)

    if (authMethodIdChosen != 255) {
      val onAuthSuccess = WhenSelectingMode(_: ConnectionSrv.ConnectionSrvActor, _: ActorRef).receive(_: ActorStateTcpUtil.State)
      val authenticator = authenticatorProviders(authMethodIdChosen).create(actor, downstreamTcp, onAuthSuccess)
      authenticator.receive(tcpUtilState)
    } else {
      log.debug("No suitable auth-method found")
      context stop self
      stdReceive.discard
    }
  }

  def handleRqDecodeErr(tcpUtilState: ActorStateTcpUtil.State, decodeError: scodec.Err): Receive = {
    log.debug("Failed to decode PDU. Shutting down with no response.")

    context stop self
    stdReceive.discard
  }

  def whenExpectingRq(tcpUtilState: ActorStateTcpUtil.State): Receive =
    tcpUtil.handleTcpReceived(downstreamTcp, authMethodSelectionRqCodec, tcpUtilState)(whenExpectingRq)(handleRq)(handleRqDecodeErr) orElse
      stdReceive.discard



  def receive(): Receive = {
    log.debug("initalizing...")
    downstreamTcp ! Tcp.Register(self)

    whenExpectingRq(ActorStateTcpUtil.State.empty)
  }

}
