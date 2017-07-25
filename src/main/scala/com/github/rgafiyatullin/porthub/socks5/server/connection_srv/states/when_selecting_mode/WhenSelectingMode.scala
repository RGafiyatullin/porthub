package com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_selecting_mode

import akka.actor.Actor.Receive
import com.github.rgafiyatullin.porthub.socks5.pdu.SocksOperation
import com.github.rgafiyatullin.porthub.socks5.pdu.SocksOperation.{SocksOperationRq, SocksOperationRs}
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.ConnectionSrv
import com.github.rgafiyatullin.porthub.socks5.security.authentication.Identity
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_mode_connect.WhenModeConnect
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.{ActorState, ActorStateTcpUtil}

final case class WhenSelectingMode(state: ConnectionSrv.State, identity: Identity)
  extends ActorState[ConnectionSrv.ConnectionSrvActor]
    with ActorStateTcpUtil[ConnectionSrv.ConnectionSrvActor]
{
  val actor = state.actor
  val stdReceive = actor.stdReceive
  val log = actor.log

  val socksOperationRqCodec = SocksOperation.encoding.socksOperationRqCodec
  val socksOperationRsCodec = SocksOperation.encoding.socksOperationRsCodec

  def handleRqCommandNotSupported(rq: SocksOperation.SocksOperationRq): Receive = {
    val response = SocksOperationRs(7.toByte /* Command not supported */, rq.address, rq.port)
    tcpUtil.write(state.downstreamTcp, socksOperationRsCodec, response)

    context stop self
    stdReceive.discard
  }

  def handleRq(tcpUtilState: ActorStateTcpUtil.State, rq: SocksOperation.SocksOperationRq): Receive = {
    log.debug("Client ({}) talks SOCKSv{} and enquires the following operation: {}", identity, rq.version, rq)

    rq.commandParsed match {
      case Some(SocksOperationRq.TcpConnect(address)) =>
        WhenModeConnect(state, identity, rq).receive(tcpUtilState)

      case Some(SocksOperationRq.TcpBind(address)) =>
        handleRqCommandNotSupported(rq)

      case Some(SocksOperationRq.UdpAssoc()) =>
        handleRqCommandNotSupported(rq)

      case None =>
        handleRqCommandNotSupported(rq)
    }
  }

  def handleRqDecodeErr(tcpUtilState: ActorStateTcpUtil.State, decodeError: scodec.Err): Receive = {
    log.debug("Failed to decode PDU. Shutting down with no response.")

    context stop self
    stdReceive.discard
  }

  def whenExpectingRq(tcpUtilState: ActorStateTcpUtil.State): Receive =
    tcpUtil.handleTcpReceived(
        state.downstreamTcp,
        socksOperationRqCodec,
        tcpUtilState)(whenExpectingRq
      )(handleRq)(handleRqDecodeErr) orElse
        stdReceive.discard

  def receive(tcpUtilState: ActorStateTcpUtil.State): Receive =
    whenExpectingRq(tcpUtilState)
}
