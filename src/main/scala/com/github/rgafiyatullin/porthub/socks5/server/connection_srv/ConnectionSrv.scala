package com.github.rgafiyatullin.porthub.socks5.server.connection_srv

import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, Props}
import akka.io.{IO, Tcp}
import akka.util.ByteString
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.{ActorFuture, ActorStdReceive}
import com.github.rgafiyatullin.porthub.socks5.pdu.{AuthMethodSelection, SocksOperation}
import com.github.rgafiyatullin.porthub.socks5.pdu.AuthMethodSelection.{AuthMethodSelectionRq, AuthMethodSelectionRs}
import com.github.rgafiyatullin.porthub.socks5.pdu.SocksOperation.{SocksOperationRq, SocksOperationRs}
import scodec.{Attempt, DecodeResult}
import scodec.bits.BitVector
import scodec.interop.akka._

object ConnectionSrv {
  final case class Args(tcpConnection: ActorRef)

  def create(args: Args)(implicit arf: ActorRefFactory): ConnectionSrv =
    ConnectionSrv(arf.actorOf(Props(classOf[ConnectionSrvActor], args)))

  object api {
    case object TryDecodingInputBuffer
  }

  final case class DecodeError(scodecErr: scodec.Err) extends Exception

  private final case class StateTcpConnected(upstreamTcpConnection: ActorRef)

  private final case class State(inputBuffer: BitVector = BitVector.empty) {
    def toTcpConnected(upstreamTcpConnection: ActorRef): StateTcpConnected =
      StateTcpConnected(upstreamTcpConnection)

    def appendToInputBuffer(byteString: ByteString): State =
      copy(inputBuffer = inputBuffer ++ byteString.toByteVector.bits)

    def replaceInputBuffer(ib: BitVector): State =
      copy(inputBuffer = ib)
  }

  class ConnectionSrvActor(args: Args)
    extends Actor
      with ActorStdReceive
      with ActorFuture
      with ActorLogging
  {
    import scodec.interop.akka._
    val authMethodSelectionRqCodec = AuthMethodSelection.encoding.authMethodSelectionRqCodec
    val authMethodSelectionRsCodec = AuthMethodSelection.encoding.authMethodSelectionRsCodec
    val socksOperationRqCodec = SocksOperation.encoding.socksOperationRqCodec
    val socksOperationRsCodec = SocksOperation.encoding.socksOperationRsCodec

    val downstreamTcpConnection = args.tcpConnection

    override def receive =
      initialize()

    def initialize(): Receive = {
      log.info("initalizing...")
      downstreamTcpConnection ! Tcp.Register(self)
      whenExpectAuthMethodSelection(State())
    }

    def handleAuthMethodSelectionPDU(state: State, rq: AuthMethodSelectionRq): Receive = {
      log.info("Client talks SOCKSv{} and supports the following auth-methods: [{}]", rq.version, rq.methods.mkString(","))
      val authMethodIdChosen = chooseAuthMethod(state, rq)
      log.info("Auth method chosen: {}", authMethodIdChosen)
      val response = AuthMethodSelectionRs(rq.version, authMethodIdChosen)
      val responseEncoded = authMethodSelectionRsCodec.encode(response).require
      downstreamTcpConnection ! Tcp.Write(responseEncoded.bytes.toByteString)

      whenExpectSocksCommand(state)
    }

    def whenExpectAuthMethodSelection(state: State): Receive =
      handleTcpReceived(state, whenExpectAuthMethodSelection, handleAuthMethodSelectionPDU, authMethodSelectionRqCodec.decode) orElse
        stdReceive.discard

    def handleTcpClosedWhenTcpConnected(state: StateTcpConnected): Receive = {
      case Tcp.PeerClosed =>
        sender() match {
          case state.upstreamTcpConnection =>
            log.debug("Upstream: peer closed")
          case `downstreamTcpConnection` =>
            log.debug("Downstream: peer closed")
        }
        context stop self
        context become stdReceive.discard

      case Tcp.ErrorClosed(reason) =>
        sender() match {
          case state.upstreamTcpConnection =>
            log.debug("Upstream error: {}", reason)
          case `downstreamTcpConnection` =>
            log.debug("Downstream error: {}", reason)
        }
        context stop self
        context become stdReceive.discard
    }

    def whenTcpConnected(state: StateTcpConnected): Receive =
      handleTcpReceivedWhenTcpConnected(state) orElse
        handleTcpClosedWhenTcpConnected(state) orElse
        stdReceive.discard


    def handleTcpReceivedWhenTcpConnected(state: StateTcpConnected): Receive = {
      case Tcp.Received(bytesReceived) =>
        sender() match {
          case state.upstreamTcpConnection =>
            log.debug("piping DOWN {} bytes", bytesReceived.size)
            downstreamTcpConnection ! Tcp.Write(bytesReceived)

          case `downstreamTcpConnection` =>
            log.debug("piping  UP  {} bytes", bytesReceived.size)
            state.upstreamTcpConnection ! Tcp.Write(bytesReceived)
        }
    }

    def handleUpstreamTcpConnected(state: State, rq: SocksOperationRq): Receive = {
      case Tcp.Connected(remoteAdress, localAdress) =>
        val upstreamTcpConnection = sender()
        upstreamTcpConnection ! Tcp.Register(self)
        upstreamTcpConnection ! Tcp.Write(state.inputBuffer.bytes.toByteString)

        log.debug("Connected {} -> {}", localAdress, remoteAdress)
        val response = SocksOperationRs(rq.version, 0 /* succeeded */, rq.address, rq.port)
        val responseEncoded = socksOperationRsCodec.encode(response).require
        downstreamTcpConnection ! Tcp.Write(responseEncoded.bytes.toByteString)

        context become whenTcpConnected(state.toTcpConnected(upstreamTcpConnection))
    }

    def handleUpstreamTcpError(state: State, rq: SocksOperationRq): Receive = {
      case Tcp.CommandFailed(_: Tcp.Connect) =>
        log.debug("Failed to connect")
        /* I wish I could figure out a slight bit more of information why the connection attempt actually failed */
        val response = SocksOperationRs(rq.version, 5 /* connection refused */, rq.address, rq.port)
        val responseEncoded = socksOperationRsCodec.encode(response).require
        downstreamTcpConnection ! Tcp.Write(responseEncoded.bytes.toByteString)

        context stop self
        context become stdReceive.discard
    }

    def whenConnectingToTcpUpstream(state: State, rq: SocksOperationRq): Receive =
      handleUpstreamTcpConnected(state, rq) orElse
        handleUpstreamTcpError(state, rq) orElse
        stdReceive.discard

    def handleSocketCommandPDU(state: State, rq: SocksOperationRq): Receive = {
      log.info("Client talks SOCKSv{} and enquires the following operation: {}", rq.version, rq)
      rq.commandParsed match {
        case Some(SocksOperationRq.TcpConnect(address)) =>
          log.debug("Connecting to {}", address)
          IO(Tcp)(context.system) ! Tcp.Connect(address)
          whenConnectingToTcpUpstream(state, rq)

        case Some(SocksOperationRq.TcpBind(address)) =>
          val response = SocksOperationRs(rq.version, 7.toByte /* Command not supported */, rq.address, rq.port)
          val responseEncoded = socksOperationRsCodec.encode(response).require
          downstreamTcpConnection ! Tcp.Write(responseEncoded.bytes.toByteString)

          context stop self
          stdReceive.discard

        case Some(SocksOperationRq.UdpAssoc()) =>
          val response = SocksOperationRs(rq.version, 7.toByte /* Command not supported */, rq.address, rq.port)
          val responseEncoded = socksOperationRsCodec.encode(response).require
          downstreamTcpConnection ! Tcp.Write(responseEncoded.bytes.toByteString)

          context stop self
          stdReceive.discard

        case None =>
          val response = SocksOperationRs(rq.version, 7.toByte /* Command not supported */, rq.address, rq.port)
          val responseEncoded = socksOperationRsCodec.encode(response).require
          downstreamTcpConnection ! Tcp.Write(responseEncoded.bytes.toByteString)

          context stop self
          stdReceive.discard
      }
    }

    def whenExpectSocksCommand(state: State): Receive =
      handleTcpReceived(state, whenExpectSocksCommand, handleSocketCommandPDU, socksOperationRqCodec.decode) orElse
        stdReceive.discard

    def handleTcpReceived[PDU](
        state: State,
        nextNoPDU: State => Receive,
        handlePDU: (State, PDU) => Receive,
        decodePDU: BitVector => Attempt[DecodeResult[PDU]]
      ): Receive =
    {
      case Tcp.Received(byteString) =>
        val stateNext = state.appendToInputBuffer(byteString)
        self ! api.TryDecodingInputBuffer
        context become nextNoPDU(stateNext)

      case api.TryDecodingInputBuffer =>
        decodePDU(state.inputBuffer) match {
          case Attempt.Successful(decodeResult) =>
            log.debug("Decoded: {}", decodeResult.value)
            val stateNext = state.replaceInputBuffer(decodeResult.remainder)

            if (decodeResult.remainder.nonEmpty)
              self ! api.TryDecodingInputBuffer

            context become handlePDU(stateNext, decodeResult.value)

          case Attempt.Failure(_: scodec.Err.InsufficientBits) =>
            context become nextNoPDU(state)

          case Attempt.Failure(decodeFailure) =>
            log.warning("Decode failure: {}", decodeFailure)
            throw DecodeError(decodeFailure)
        }
    }

    def chooseAuthMethod(state: State, rq: AuthMethodSelectionRq): Byte = 0.toByte
  }
}

final case class ConnectionSrv(actorRef: ActorRef) {

}
