package com.github.rgafiyatullin.porthub.socks5.server.connection_srv_2.protocol_srv

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, Props}
import akka.io.Tcp
import akka.pattern.AskTimeoutException
import akka.util.Timeout
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.{ActorFuture, ActorStdReceive}
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv_2.protocol_srv.ProtocolSrv.State.ReadPacketCtx
import scodec.{Attempt, Codec}
import scodec.bits.BitVector

import scala.collection.immutable.Queue
import scala.concurrent.Promise

object ProtocolSrv {

  def create(args: Args, name: String = "")(arf: ActorRefFactory): ProtocolSrv =
    if (name.isEmpty)
      ProtocolSrv(arf.actorOf(Props(classOf[ProtocolSrvActor], args)))
    else
      ProtocolSrv(arf.actorOf(Props(classOf[ProtocolSrvActor], args), name))

  final case class Args(connectionActorRef: ActorRef)

  final case class ShutdownOnTcpError() extends Exception

  object api {
    sealed trait ReadPacket {
      def timeoutOption: Option[Timeout]
      def toCtx: State.ReadPacketCtx
    }

    final case class ReadPacketImpl[PDU](codec: Codec[PDU], promise: Promise[PDU], timeoutOption: Option[Timeout])
      extends ReadPacket
    {
      override def toCtx: State.ReadPacketCtx =
        State.ReadPacketCtxImpl(codec, promise)
    }

    sealed trait WritePacket {
      def toBitVector: Either[scodec.Err, BitVector]
    }
    object WritePacket {
      final case class EncodeError(encodeError: scodec.Err) extends Exception
    }

    final case class WritePacketImpl[PDU](codec: Codec[PDU], pdu: PDU) extends WritePacket {
      override def toBitVector =
        codec.encode(pdu) match {
          case Attempt.Successful(bitVector) => Right(bitVector)
          case Attempt.Failure(err) => Left(err)
        }
    }

    case object Close

    final case class TimeoutOnInputReader(id: UUID)

  }

  object State {
    sealed trait ReadPacketCtx {
      def fail(failure: Throwable): Unit

      val id: UUID
      def process(bitVectorIn: BitVector): Either[scodec.Err, Option[BitVector]]
    }

    object ReadPacketCtx {
      final case class DecodeError(err: scodec.Err) extends Exception
      final case class EOF() extends Exception
    }

    final case class ReadPacketCtxImpl[PDU](
      codec: Codec[PDU],
      promise: Promise[PDU]) extends ReadPacketCtx
    {
      override val id: UUID = UUID.randomUUID()

      override def fail(failure: Throwable): Unit = {
        promise.failure(failure)
        ()
      }

      override def process(bitVectorIn: BitVector): Either[scodec.Err, Option[BitVector]] =
        codec.decode(bitVectorIn) match {
          case Attempt.Successful(decodeResult) =>
            promise.success(decodeResult.value)
            Right(Some(decodeResult.remainder))

          case Attempt.Failure(_: scodec.Err.InsufficientBits) =>
            Right(None)

          case Attempt.Failure(decodeErr) =>
            fail(ReadPacketCtx.DecodeError(decodeErr))

            Left(decodeErr)
        }
    }

    final case class StateInput(
      inputBuffer: BitVector = BitVector.empty,
      inputReaders: Queue[ReadPacketCtx] = Queue.empty)
    {
      def filterOnTimeout(id: UUID): StateInput = {
        val (timedOut, stillOnTime) = inputReaders.partition(_.id == id)
        timedOut.foreach(
          _.fail(
            new AskTimeoutException(
              "Failed to read complete packet in time [Current buffer size: %d bits]"
                .format(inputBuffer.size))))

        copy(inputReaders = stillOnTime)
      }

      def appendBits(appendee: BitVector): StateInput =
        copy(inputBuffer = inputBuffer ++ appendee)

      def appendReader(appendee: ReadPacketCtx): StateInput =
        copy(inputReaders = inputReaders.enqueue(appendee))

      def process: Either[scodec.Err, StateInput] =
        inputReaders.headOption match {
          case None =>
            Right(this)

          case Some(readCtx) =>
            readCtx.process(inputBuffer) match {
              case Left(err) =>
                inputReaders.tail.foreach(
                  _.fail(ReadPacketCtx.DecodeError(err)))

                Left(err)

              case Right(None) =>
                Right(this)

              case Right(Some(inputBufferNext)) =>
                copy(
                  inputBuffer = inputBufferNext,
                  inputReaders = inputReaders.tail).process
            }
        }
    }
  }

  final case class State(
    input: State.StateInput = State.StateInput())
  {
    def withInput(inputNext: State.StateInput): State =
      copy(input = inputNext)
  }

  final class ProtocolSrvActor(args: Args)
    extends Actor
      with ActorFuture
      with ActorStdReceive
      with ActorLogging
  {
    val io = args.connectionActorRef

    override def receive = initialize()

    def initialize(): Receive = {
      io ! Tcp.Register(self)

      val initialState = State()
      whenReady(initialState)
    }

    def handleReadPacket(state: State): Receive = {
      case readPacket: api.ReadPacket =>
        val readCtx = readPacket.toCtx

        readPacket
          .timeoutOption
          .map(_.duration)
          .foreach(
            context.system.scheduler
              .scheduleOnce(_, self, api.TimeoutOnInputReader(readCtx.id)))

        state.input.appendReader(readCtx).process match {
          case Right(stateInputNext) =>
            val stateNext = state.withInput(stateInputNext)
            context become whenReady(stateNext)

          case Left(decodeErr) =>
            log.warning("Decode error: {}", decodeErr)
            context stop self
            context become stdReceive.discard
        }

      case api.TimeoutOnInputReader(id) =>
        state.withInput(state.input.filterOnTimeout(id))
    }

    def handleTcpReceived(state: State): Receive = {
      case Tcp.Received(bytesIn) =>
        import scodec.interop.akka._
        state.input.appendBits(bytesIn.toByteVector.bits).process match {
          case Right(stateInputNext) =>
            val stateNext = state.withInput(stateInputNext)
            context become whenReady(stateNext)

          case Left(decodeErr) =>
            context become onDecodeError(decodeErr, state)
        }
    }

    def handleTcpPeerClosed(state: State): Receive = {
      case Tcp.PeerClosed =>
        reportEofAtAllReaders(state)
        context stop self
        context become stdReceive.discard
    }

    def handleTcpError(state: State): Receive = {
      case Tcp.ErrorClosed =>
        reportEofAtAllReaders(state)
        val failure = ShutdownOnTcpError()

        throw failure
    }

    def reportEofAtAllReaders(state: State): Unit =
      state.input.inputReaders.foreach(_.fail(ReadPacketCtx.EOF()))

    def onDecodeError(decodeErr: scodec.Err, state: State): Receive = {
      log.warning("Decode error: {}", decodeErr)
      val failure = ReadPacketCtx.DecodeError(decodeErr)

      throw failure
    }

    def handleWritePacket(state: State): Receive = {
      case writePacket: api.WritePacket =>
        writePacket.toBitVector match {
          case Left(encodeError) =>
            val failure = api.WritePacket.EncodeError(encodeError)
            reportEofAtAllReaders(state)

            throw failure

          case Right(bitVector) =>
            import scodec.interop.akka._
            val bytesOut = bitVector.bytes.toByteString
            io ! Tcp.Write(bytesOut)

            context become whenReady(state)
        }
    }

    def handleClose(state: State): Receive = {
      case api.Close =>
        reportEofAtAllReaders(state)
        context stop self
        context become stdReceive.discard
    }

    def whenReady(state: State): Receive =
      handleReadPacket(state) orElse
        handleWritePacket(state) orElse
        handleTcpReceived(state) orElse
        handleTcpPeerClosed(state) orElse
        handleTcpError(state) orElse
        handleClose(state) orElse
        stdReceive.discard

  }
}

final case class ProtocolSrv(actorRef: ActorRef) {
  import ProtocolSrv.api
  def readPacket[PDU](codec: Codec[PDU], promise: Promise[PDU], timeoutOption: Option[Timeout] = None): Unit =
    actorRef ! api.ReadPacketImpl(codec, promise, timeoutOption)

  def writePacket[PDU](codec: Codec[PDU], pdu: PDU): Unit =
    actorRef ! api.WritePacketImpl(codec, pdu)

  def close(): Unit =
    actorRef ! api.Close
}
