package com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.io.Tcp
import com.github.rgafiyatullin.porthub.socks5.pdu.AuthMethodSelection
import scodec.{Attempt, Codec, DecodeResult}
import scodec.bits.BitVector
import scodec.interop.akka._

object ActorStateTcpUtil {
  object State {
    def empty: State = State()
  }
  final case class State(buffers: Map[ActorRef, BitVector] = Map.empty) {
    def appendToBuffer(fromTcp: ActorRef, appendee: BitVector): State =
      updateBuffer(fromTcp, mapBuffer(fromTcp)(_ ++ appendee))

    def mapBuffer[RetType](fromTcp: ActorRef)(f: BitVector => RetType): RetType =
      f(buffers.getOrElse(fromTcp, BitVector.empty))

    def updateBuffer(fromTcp: ActorRef, buffer: BitVector): State =
      copy(
        buffers =
          if (buffer.isEmpty)
            buffers - fromTcp
          else
            buffers + (fromTcp -> buffer))
  }


  final case class TryDecodeInputBuffer(fromTcp: ActorRef)
}

trait ActorStateTcpUtil[ActorType <: Actor with ActorLogging]
  extends ActorState[ActorType]
{
  import ActorStateTcpUtil.State

  object tcpUtil {

    def ignoreTryDecodeInputBufferMessages(fromTcp: ActorRef): Receive = {
      case ActorStateTcpUtil.TryDecodeInputBuffer(`fromTcp`) =>
        actor.log.debug("Ignoring TryDecodeInputBuffer({}) [sender => {}]", fromTcp, sender())
        ()
    }

    def write[PDUType](toTcp: ActorRef, codec: Codec[PDUType], pdu: PDUType): Unit =
      toTcp ! Tcp.Write(codec.encode(pdu).require.bytes.toByteString)

    def handleTcpReceived[PDUType](fromTcp: ActorRef, codec: Codec[PDUType], state: State)
                                  (receiveWhenNoPDU: State => Receive)
                                  (handlePDU: (State, PDUType) => Receive)
                                  (handleDecodeError: (State, scodec.Err) => Receive): Receive =
      new PartialFunction[Any, Unit] {

        override def isDefinedAt(msg: Any) =
          msg match {
            case _: Tcp.Received =>
              sender() == fromTcp

            case ActorStateTcpUtil.TryDecodeInputBuffer(`fromTcp`) =>
              true

            case _ =>
              false
          }

        override def apply(message: Any) =
          message match {
            case Tcp.Received(byteString) =>
              actor.log.debug("{} => Tcp.Received({})", sender(), byteString)
              assert(sender() == fromTcp)
              val stateNext = state.appendToBuffer(fromTcp, byteString.toByteVector.bits)

              self ! ActorStateTcpUtil.TryDecodeInputBuffer(fromTcp)
              actor.log.debug("TcpBuffs: {}", stateNext)
              context become receiveWhenNoPDU(stateNext)

            case ActorStateTcpUtil.TryDecodeInputBuffer(`fromTcp`) =>
              actor.log.debug("Trying to decode {} => {}", fromTcp, state.mapBuffer(fromTcp)(identity))
              context become state.mapBuffer(fromTcp) { ib0 =>
                val decodeAttempt = codec.decode(ib0)
                decodeAttempt match {
                  case Attempt.Successful(DecodeResult(pdu, ib1)) =>
                    val stateNext = state.updateBuffer(fromTcp, ib1)
                    self ! ActorStateTcpUtil.TryDecodeInputBuffer(fromTcp)
                    handlePDU(stateNext, pdu)

                  case Attempt.Failure(_: scodec.Err.InsufficientBits) =>
                    receiveWhenNoPDU(state)

                  case Attempt.Failure(decodeError) =>
                    handleDecodeError(state, decodeError)
                }
              }
          }
      }

  }
}
