package com.github.rgafiyatullin.porthub.socks5.server.top_sup

import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, Props}
import akka.util.Timeout
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.{ActorFuture, ActorStdReceive}
import com.github.rgafiyatullin.porthub.socks5.server.connection_sup.ConnectionSup
import com.github.rgafiyatullin.porthub.socks5.server.listener_srv.ListenerSrv
import akka.actor.Status
import com.github.rgafiyatullin.porthub.socks5.server.Config
import com.github.rgafiyatullin.porthub.socks5.server.authentication_srv.AuthenticationSrv

import scala.concurrent.Future

object TopSup {
  final case class Args(config: Config)

  object api {
    case object GetListenerSrv
    case object GetConnectionSup
    case object GetAuthenticationSrv
  }

  def create(args: Args)(implicit arf: ActorRefFactory): TopSup =
    TopSup(arf.actorOf(Props(classOf[TopSupActor], args)))

  private final case class State(
    connectionSup: ConnectionSup,
    listenerSrv: ListenerSrv,
    authenticationSrv: AuthenticationSrv)

  class TopSupActor(args: Args)
    extends Actor
      with ActorStdReceive
      with ActorFuture
      with ActorLogging
  {
    override def receive =
      initialize()

    def initialize(): Receive = {
      log.info("initializing...")
      val authenticationSrv = AuthenticationSrv.create(AuthenticationSrv.Args(args.config.authentication))
      val connectionSup = ConnectionSup.create(ConnectionSup.Args(args.config, authenticationSrv))
      val listenerSrv = ListenerSrv.create(ListenerSrv.Args(args.config, connectionSup))

      val state0 = State(connectionSup, listenerSrv, authenticationSrv)
      log.info("initialized")
      whenReady(state0)
    }

    def whenReady(state: State): Receive =
      handleGetListenerSrv(state) orElse
        handleGetConnectionSup(state) orElse
        handleGetAuthenticationSrv(state) orElse
        stdReceive.discard

    def handleGetListenerSrv(state: State): Receive = {
      case api.GetListenerSrv =>
        sender() ! Status.Success(state.listenerSrv)
    }

    def handleGetConnectionSup(state: State): Receive = {
      case api.GetConnectionSup =>
        sender() ! Status.Success(state.connectionSup)
    }

    def handleGetAuthenticationSrv(state: State): Receive = {
      case api.GetAuthenticationSrv =>
        sender() ! Status.Success(state.authenticationSrv)
    }
  }
}

final case class TopSup(actorRef: ActorRef) {
  import akka.pattern.ask

  def getConnectionSup()(implicit timeout: Timeout): Future[ConnectionSup] =
    actorRef.ask(TopSup.api.GetConnectionSup).mapTo[ConnectionSup]

  def getListenerSrv()(implicit timeout: Timeout): Future[ListenerSrv] =
    actorRef.ask(TopSup.api.GetListenerSrv).mapTo[ListenerSrv]

  def getAuthenticationSrv()(implicit timeout: Timeout): Future[AuthenticationSrv] =
    actorRef.ask(TopSup.api.GetAuthenticationSrv).mapTo[AuthenticationSrv]
}
