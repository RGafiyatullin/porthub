package com.github.rgafiyatullin.porthub.socks5.server.connection_sup.states.when_mode_connect

import akka.actor.Actor.Receive
import akka.actor.ActorRef
import akka.io.{IO, Tcp}
import com.github.rgafiyatullin.porthub.socks5.pdu.SocksOperation
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.ConnectionSrv
import com.github.rgafiyatullin.porthub.socks5.server.connection_sup.states.{ActorState, ActorStateTcpUtil}
import scodec.interop.akka._

object WhenModeConnect {
  final case class Connecting(
      actor: ConnectionSrv.ConnectionSrvActor,
      downstreamTcp: ActorRef,
      rq: SocksOperation.SocksOperationRq)
    extends ActorState[ConnectionSrv.ConnectionSrvActor]
      with ActorStateTcpUtil[ConnectionSrv.ConnectionSrvActor]
  {
    val stdReceive = actor.stdReceive
    val log = actor.log

    val socksOperationRsCodec = SocksOperation.encoding.socksOperationRsCodec

    def receive(tcpUtilState: ActorStateTcpUtil.State): Receive = {
      log.debug("Connecting to {}...", rq.inetSocketAddress)
      IO(Tcp)(context.system).tell(Tcp.Connect(rq.inetSocketAddress), self)

      whenConnecting(tcpUtilState)
    }

    def whenConnecting(tcpUtilState: ActorStateTcpUtil.State): Receive =
      tcpUtil.ignoreTryDecodeInputBufferMessages(downstreamTcp) orElse
        handleTcpConnected(tcpUtilState: ActorStateTcpUtil.State) orElse
        handleTcpConnectionFailed() orElse
        stdReceive.discard

    def handleTcpConnected(tcpUtilState: ActorStateTcpUtil.State): Receive = {
      case Tcp.Connected(remoteAddress, localAddress) =>
        log.debug("Connected {} -> {}", localAddress, remoteAddress)

        val upstreamTcp = sender()
        upstreamTcp ! Tcp.Register(self)
        upstreamTcp ! Tcp.Write(tcpUtilState.mapBuffer(downstreamTcp)(identity).bytes.toByteString)

        val response = SocksOperation.SocksOperationRs(rq.version, 0 /* succeeded */, rq.address, rq.port)
        tcpUtil.write(downstreamTcp, socksOperationRsCodec, response)

        context become Connected(actor, downstreamTcp, upstreamTcp).receive()
    }

    def handleTcpConnectionFailed(): Receive = {
      case Tcp.CommandFailed(_: Tcp.Connect) =>
        log.debug("Failed to connect")
        /* I wish I could figure out a slight bit more of information why the connection attempt actually failed */
        val response = SocksOperation.SocksOperationRs(rq.version, 5 /* connection refused */, rq.address, rq.port)
        tcpUtil.write(downstreamTcp, socksOperationRsCodec, response)

        context stop self
        context become stdReceive.discard
    }
  }

  final case class Connected(
      actor: ConnectionSrv.ConnectionSrvActor,
      downstreamTcp: ActorRef,
      upstreamTcp: ActorRef)
    extends ActorState[ConnectionSrv.ConnectionSrvActor]
  {
    val stdReceive = actor.stdReceive
    val log = actor.log

    def receive(): Receive =
      handleTcpReceived() orElse
        handleTcpClosed() orElse 
        stdReceive.discard

    def handleTcpReceived(): Receive = {
      case Tcp.Received(bytesReceived) =>
        sender() match {
          case `upstreamTcp` =>
            log.debug("piping DOWN {} bytes", bytesReceived.size)
            downstreamTcp ! Tcp.Write(bytesReceived)

          case `downstreamTcp` =>
            log.debug("piping  UP  {} bytes", bytesReceived.size)
            upstreamTcp ! Tcp.Write(bytesReceived)
        }
    }

    def handleTcpClosed(): Receive = {
      case Tcp.PeerClosed =>
        sender() match {
          case `upstreamTcp` =>
            log.debug("Upstream: peer closed")
          case `downstreamTcp` =>
            log.debug("Downstream: peer closed")
        }
        context stop self
        context become stdReceive.discard

      case Tcp.ErrorClosed(reason) =>
        sender() match {
          case `upstreamTcp` =>
            log.debug("Upstream error: {}", reason)
          case `downstreamTcp` =>
            log.debug("Downstream error: {}", reason)
        }
        context stop self
        context become stdReceive.discard
    }
  }
}

final case class WhenModeConnect(actor: ConnectionSrv.ConnectionSrvActor, downstreamTcp: ActorRef, rq: SocksOperation.SocksOperationRq)
  extends ActorState[ConnectionSrv.ConnectionSrvActor]
    with ActorStateTcpUtil[ConnectionSrv.ConnectionSrvActor]
{
  def receive(tcpUtilState: ActorStateTcpUtil.State): Receive =
    WhenModeConnect.Connecting(actor, downstreamTcp, rq).receive(tcpUtilState)
}
