package com.github.rgafiyatullin.porthub.socks5.server.connection_sup.states.when_selecting_mode

import akka.actor.Actor.Receive
import akka.actor.ActorRef
import com.github.rgafiyatullin.porthub.socks5.pdu.SocksOperation
import com.github.rgafiyatullin.porthub.socks5.pdu.SocksOperation.{SocksOperationRq, SocksOperationRs}
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.ConnectionSrv
import com.github.rgafiyatullin.porthub.socks5.server.connection_sup.states.when_mode_connect.WhenModeConnect
import com.github.rgafiyatullin.porthub.socks5.server.connection_sup.states.{ActorState, ActorStateTcpUtil}

final case class WhenSelectingMode(actor: ConnectionSrv.ConnectionSrvActor, downstreamTcp: ActorRef)
  extends ActorState[ConnectionSrv.ConnectionSrvActor]
    with ActorStateTcpUtil[ConnectionSrv.ConnectionSrvActor]
{
  val stdReceive = actor.stdReceive
  val log = actor.log

  val socksOperationRqCodec = SocksOperation.encoding.socksOperationRqCodec
  val socksOperationRsCodec = SocksOperation.encoding.socksOperationRsCodec

  def handleRqCommandNotSupported(rq: SocksOperation.SocksOperationRq): Receive = {
    val response = SocksOperationRs(rq.version, 7.toByte /* Command not supported */, rq.address, rq.port)
    tcpUtil.write(downstreamTcp, socksOperationRsCodec, response)

    context stop self
    stdReceive.discard
  }

  def handleRq(tcpUtilState: ActorStateTcpUtil.State, rq: SocksOperation.SocksOperationRq): Receive = {
    log.info("Client talks SOCKSv{} and enquires the following operation: {}", rq.version, rq)

    rq.commandParsed match {
      case Some(SocksOperationRq.TcpConnect(address)) =>
        WhenModeConnect(actor, downstreamTcp, rq).receive(tcpUtilState)

      case Some(SocksOperationRq.TcpBind(address)) =>
        handleRqCommandNotSupported(rq)

      case Some(SocksOperationRq.UdpAssoc()) =>
        handleRqCommandNotSupported(rq)

      case None =>
        handleRqCommandNotSupported(rq)
    }
  }

  def handleRqDecodeErr(tcpUtilState: ActorStateTcpUtil.State, decodeError: scodec.Err): Receive = {
    log.info("Failed to decode PDU. Shutting down with no response.")

    context stop self
    stdReceive.discard
  }

  def whenExpectingRq(tcpUtilState: ActorStateTcpUtil.State): Receive =
    tcpUtil.handleTcpReceived(downstreamTcp, socksOperationRqCodec, tcpUtilState)(whenExpectingRq)(handleRq)(handleRqDecodeErr) orElse
      stdReceive.discard

  def receive(tcpUtilState: ActorStateTcpUtil.State): Receive =
    whenExpectingRq(tcpUtilState)
}