package com.github.rgafiyatullin.porthub.socks5.server.listener_srv

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, Props}
import akka.io.{IO, Tcp}
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.{ActorFuture, ActorStdReceive}
import com.github.rgafiyatullin.porthub.socks5.server.Config
import com.github.rgafiyatullin.porthub.socks5.server.connection_sup.ConnectionSup

object ListenerSrv {
  final case class Args(config: Config, connectionSup: ConnectionSup)

  def create(args: Args)(implicit arf: ActorRefFactory): ListenerSrv =
    ListenerSrv(arf.actorOf(Props(classOf[ListenerSrvActor], args)))

  object api {
    case object BindTimeout extends Exception
  }

  private final case class State()

  class ListenerSrvActor(args: Args)
    extends Actor
      with ActorFuture
      with ActorStdReceive
      with ActorLogging
  {
    implicit val executionContext = context.dispatcher
    implicit val defaultOperationTimeout = args.config.defaultOperationTimeout

    override def receive =
      initialize()


    def initialize(): Receive = {
      IO(Tcp)(context.system) ! Tcp.Bind(self, new InetSocketAddress(args.config.bind.port))
      context.system.scheduler.scheduleOnce(args.config.bind.timeout, self, api.BindTimeout)
      whenBinding()
    }

    def whenBinding(): Receive = {
      case Tcp.Bound(boundAddress) =>
        log.info("Bound at {}", boundAddress)
        context become whenReady(State())

      case bindTimeout @ api.BindTimeout =>
        log.error("Bind operation timed out")
        throw bindTimeout
    }

    def whenReady(state: State): Receive =
      ignoreBindTimeout(state) orElse
        handleConnected(state) orElse
        stdReceive.discard

    def ignoreBindTimeout(state: State): Receive = {
      case api.BindTimeout =>
        context become whenReady(state)
    }

    def handleConnected(state: State): Receive = {
      case Tcp.Connected(remoteAddress, localAddress) =>
        val tcpConnection = sender()
        val _ = for {
          connectionSrv <- args.connectionSup.startConnectionSrv(tcpConnection)
        }
          yield ()

        context become whenReady(state)
    }
  }
}

final case class ListenerSrv(actorRef: ActorRef) {}
