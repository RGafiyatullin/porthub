package com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_mode_connect

import java.net.InetSocketAddress

import akka.actor.Actor.Receive
import akka.actor.ActorRef
import akka.io.{IO, Tcp}
import com.github.rgafiyatullin.porthub.socks5.pdu.SocksOperation
import com.github.rgafiyatullin.porthub.socks5.security.audit.Event
import com.github.rgafiyatullin.porthub.socks5.security.authentication.Identity
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.ConnectionSrv
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.{ActorState, ActorStateTcpUtil}
import org.joda.time.DateTime
import scodec.interop.akka._

object WhenModeConnect {
  object auditEvents {
    final case class Connecting(
      identity: Identity,
      connectionActorRef: ActorRef,
      downstreamSocketAddr: InetSocketAddress,
      upstreamSocketAddr: InetSocketAddress,
      at: DateTime = DateTime.now())
        extends Event.WithDownstreamSocketAddr
          with Event.WithUpstreamSocketAddr
          with Event.WithIdentity
          with Event.WithConnection
    {
      override def toString =
        s"[$at] $identity connecting to $upstreamSocketAddr [via: $connectionActorRef; from: $downstreamSocketAddr]"
    }


    final case class ConnectionFailed(
      identity: Identity,
      connectionActorRef: ActorRef,
      downstreamSocketAddr: InetSocketAddress,
      upstreamSocketAddr: InetSocketAddress,
      at: DateTime = DateTime.now())
        extends Event.WithDownstreamSocketAddr
          with Event.WithUpstreamSocketAddr
          with Event.WithIdentity
          with Event.WithConnection
    {
      override def toString =
        s"[$at] $identity failed to connect to $upstreamSocketAddr [via: $connectionActorRef; from: $downstreamSocketAddr]"
    }

    final case class Connected(
      identity: Identity,
      connectionActorRef: ActorRef,
      downstreamSocketAddr: InetSocketAddress,
      upstreamSocketAddr: InetSocketAddress,
      actualUpstreamSocketAddr: InetSocketAddress,
      at: DateTime = DateTime.now())
        extends Event.WithDownstreamSocketAddr
          with Event.WithUpstreamSocketAddr
          with Event.WithIdentity
          with Event.WithConnection
    {
      override def toString =
        s"[$at] $identity connected to $upstreamSocketAddr [via: $connectionActorRef; from: $downstreamSocketAddr]"
    }


  }


  final case class Connecting(
      state: ConnectionSrv.State,
      identity: Identity,
      rq: SocksOperation.SocksOperationRq)
    extends ActorState[ConnectionSrv.ConnectionSrvActor]
      with ActorStateTcpUtil[ConnectionSrv.ConnectionSrvActor]
  {
    val actor = state.actor
    val stdReceive = actor.stdReceive
    val log = actor.log
    val auditSrv = state.auditSrv

    val socksOperationRsCodec = SocksOperation.encoding.socksOperationRsCodec

    def receive(tcpUtilState: ActorStateTcpUtil.State): Receive = {
      log.info("Connecting to {}...", rq.inetSocketAddress)

      auditSrv.report(
        auditEvents.Connecting(
          identity,
          self,
          state.downstreamSocketAddress,
          rq.inetSocketAddress))

      IO(Tcp)(context.system) ! Tcp.Connect(rq.inetSocketAddress)

      whenConnecting(tcpUtilState)
    }

    def whenConnecting(tcpUtilState: ActorStateTcpUtil.State): Receive =
      tcpUtil.ignoreTryDecodeInputBufferMessages(state.downstreamTcp) orElse
        handleTcpConnected(tcpUtilState: ActorStateTcpUtil.State) orElse
        handleTcpConnectionFailed() orElse
        stdReceive.discard

    def handleTcpConnected(tcpUtilState: ActorStateTcpUtil.State): Receive = {
      case Tcp.Connected(remoteAddress, localAddress) =>
        log.debug("Connected {} -> {}", localAddress, remoteAddress)

        auditSrv.report(
          auditEvents.Connected(
            identity,
            self,
            state.downstreamSocketAddress,
            rq.inetSocketAddress,
            remoteAddress))

        val upstreamTcp = sender()
        upstreamTcp ! Tcp.Register(self)
        upstreamTcp ! Tcp.Write(tcpUtilState.mapBuffer(state.downstreamTcp)(Predef.identity).bytes.toByteString)

        val response = SocksOperation.SocksOperationRs(0 /* succeeded */, rq.address, rq.port)
        tcpUtil.write(state.downstreamTcp, socksOperationRsCodec, response)

        context become Connected(state, identity, upstreamTcp, rq.inetSocketAddress, remoteAddress).receive()
    }

    def handleTcpConnectionFailed(): Receive = {
      case Tcp.CommandFailed(_: Tcp.Connect) =>
        log.debug("Failed to connect")

        auditSrv.report(
          auditEvents.ConnectionFailed(
            identity,
            self,
            state.downstreamSocketAddress,
            rq.inetSocketAddress))

        /* I wish I could figure out a slight bit more of information why the connection attempt actually failed */
        val response = SocksOperation.SocksOperationRs(5 /* connection refused */, rq.address, rq.port)
        tcpUtil.write(state.downstreamTcp, socksOperationRsCodec, response)

        context stop self
        context become stdReceive.discard
    }
  }

  final case class Connected(
      state: ConnectionSrv.State,
      identity: Identity,
      upstreamTcp: ActorRef,
      requestedUpstreamSocketAddr: InetSocketAddress,
      actualUpstreamSocketAddr: InetSocketAddress)
    extends ActorState[ConnectionSrv.ConnectionSrvActor]
  {
    val actor = state.actor
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
            state.downstreamTcp ! Tcp.Write(bytesReceived)

          case state.downstreamTcp =>
            log.debug("piping  UP  {} bytes", bytesReceived.size)
            upstreamTcp ! Tcp.Write(bytesReceived)
        }
    }

    def handleTcpClosed(): Receive = {
      case Tcp.PeerClosed =>
        sender() match {
          case `upstreamTcp` =>
            log.debug("Upstream: peer closed")
          case state.downstreamTcp =>
            log.debug("Downstream: peer closed")
        }
        context stop self
        context become stdReceive.discard

      case Tcp.ErrorClosed(reason) =>
        sender() match {
          case `upstreamTcp` =>
            log.debug("Upstream error: {}", reason)
          case state.downstreamTcp =>
            log.debug("Downstream error: {}", reason)
        }
        context stop self
        context become stdReceive.discard
    }
  }
}

final case class WhenModeConnect(state: ConnectionSrv.State, identity: Identity, rq: SocksOperation.SocksOperationRq)
  extends ActorState[ConnectionSrv.ConnectionSrvActor]
    with ActorStateTcpUtil[ConnectionSrv.ConnectionSrvActor]
{
  val actor = state.actor

  def receive(tcpUtilState: ActorStateTcpUtil.State): Receive =
    WhenModeConnect.Connecting(state, identity, rq).receive(tcpUtilState)
}
