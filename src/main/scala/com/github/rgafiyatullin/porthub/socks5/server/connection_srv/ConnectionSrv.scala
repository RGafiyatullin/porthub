package com.github.rgafiyatullin.porthub.socks5.server.connection_srv

import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, Props}
import akka.event.LoggingReceive
import akka.util.{ByteString, Timeout}
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.{ActorFuture, ActorStdReceive}
import com.github.rgafiyatullin.porthub.socks5.server.authentication_srv.AuthenticationSrv
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_authenticating.WhenAuthenticating
import scodec.bits.BitVector
import scodec.interop.akka._

object ConnectionSrv {
  final case class Args(
    tcpConnection: ActorRef,
    authenticationSrv: AuthenticationSrv,
    operationTimeout: Timeout)

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
    override def receive =
      LoggingReceive(
        WhenAuthenticating(
          this,
          args.tcpConnection,
          args.authenticationSrv,
          args.operationTimeout).receive())
  }
}

final case class ConnectionSrv(actorRef: ActorRef) {

}
