package com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_authenticating

import akka.actor.Actor.Receive
import akka.actor.ActorRef
import akka.io.Tcp
import com.github.rgafiyatullin.porthub.socks5.pdu.AuthMethodSelection
import com.github.rgafiyatullin.porthub.socks5.pdu.AuthMethodSelection.AuthMethodSelectionRs
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.ConnectionSrv
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_selecting_mode.WhenSelectingMode
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.{ActorState, ActorStateTcpUtil}

final case class WhenAuthenticating(actor: ConnectionSrv.ConnectionSrvActor, downstreamTcp: ActorRef)
  extends ActorState[ConnectionSrv.ConnectionSrvActor]
    with ActorStateTcpUtil[ConnectionSrv.ConnectionSrvActor]
{
  val stdReceive = actor.stdReceive
  val log = actor.log

  val authMethodSelectionRqCodec = AuthMethodSelection.encoding.authMethodSelectionRqCodec
  val authMethodSelectionRsCodec = AuthMethodSelection.encoding.authMethodSelectionRsCodec


  def chooseAuthMethod(rq: AuthMethodSelection.AuthMethodSelectionRq): Byte = 0.toByte


  def handleRq(tcpUtilState: ActorStateTcpUtil.State, rq: AuthMethodSelection.AuthMethodSelectionRq): Receive = {
    log.debug("Client talks SOCKSv{} and supports the following auth-methods: [{}]", rq.version, rq.methods.mkString(","))
    val authMethodIdChosen = chooseAuthMethod(rq)
    log.debug("Auth method chosen: {}", authMethodIdChosen)
    val response = AuthMethodSelectionRs(authMethodIdChosen)

    tcpUtil.write(downstreamTcp, authMethodSelectionRsCodec, response)
    WhenSelectingMode(actor, downstreamTcp).receive(tcpUtilState)
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
