package com.github.rgafiyatullin.porthub.socks5.server.connection_sup

import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, Props, Status, SupervisorStrategy}
import akka.util.Timeout
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.{ActorFuture, ActorStdReceive}
import com.github.rgafiyatullin.porthub.socks5.server.Config
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.ConnectionSrv

import scala.concurrent.Future

object ConnectionSup {
  final case class Args(config: Config)

  def create(args: Args)(implicit arf: ActorRefFactory): ConnectionSup =
    ConnectionSup(arf.actorOf(Props(classOf[ConnectionSupActor], args)))


  object api {
    final case class StartConnectionSrv(tcpConnection: ActorRef)
  }

  private final case class State()

  class ConnectionSupActor(args: Args)
    extends Actor
      with ActorFuture
      with ActorStdReceive
      with ActorLogging
  {
    override def supervisorStrategy =
      SupervisorStrategy.stoppingStrategy

    override def receive =
      initialize()

    def initialize(): Receive =
      whenReady(State())


    def whenReady(state: State): Receive =
      handleStartConnectionSrv(state) orElse
        stdReceive.discard


    def handleStartConnectionSrv(state: State): Receive = {
      case api.StartConnectionSrv(tcpConnection) =>
        val connectionSrv = ConnectionSrv.create(ConnectionSrv.Args(tcpConnection))
        sender() ! Status.Success(connectionSrv)
        context become whenReady(state)
    }
  }
}

final case class ConnectionSup(actorRef: ActorRef) {
  import akka.pattern.ask

  def startConnectionSrv(tcpConnection: ActorRef)(implicit timeout: Timeout): Future[ConnectionSrv] =
    actorRef.ask(ConnectionSup.api.StartConnectionSrv(tcpConnection)).mapTo[ConnectionSrv]
}
