package com.github.rgafiyatullin.porthub.socks5.server.authentication_srv

import java.io.File

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props, Status}
import akka.util.Timeout
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.ActorStdReceive
import com.github.rgafiyatullin.porthub.socks5.server.Config

import scala.concurrent.Future
import scala.io.Source

object AuthenticationSrv {
  final case class Args(config: Config.Authentication)

  def create(args: Args)(implicit arf: ActorRefFactory): AuthenticationSrv =
    AuthenticationSrv(arf.actorOf(Props(classOf[AuthenticationSrvActor], args)))

  final case class State(passwords: Set[(String, String)] = Set.empty)

  final class AuthenticationSrvActor(args: Args) extends Actor with ActorStdReceive {
    def handleUsernamePasswordRequests(state: State): Receive = {
      case api.usernamePassword.Check(u, p) =>
        sender() ! Status.Success(state.passwords.contains(u,p))
    }

    def whenReady(state: State): Receive =
      handleUsernamePasswordRequests(state) orElse
        stdReceive.discard

    override def receive =
      initialize()

    def initializePasswords(): Set[(String, String)] =
      args
        .config
        .usernamePasswordOption
        .fold(Set.empty[(String,String)]){ config =>
          val source = Source.fromFile(config.file)
          val lines = source.getLines().toSeq
          lines.map { line =>
              val pair = line.split("\\s+", 2)
              if (pair.length == 2) {
                log.debug("Storing password auth for '{}'", pair(0))
                Some(pair(0), pair(1))
              }
              else
                None
            }
            .collect { case Some(defined) => defined }.toSet
        }

    def initialize(): Receive =
      whenReady(State(
        passwords = initializePasswords()
      ))

  }

  object api {
    object usernamePassword {
      final case class Check(u: String, p: String)
    }
  }

}

final case class AuthenticationSrv(actorRef: ActorRef) {
  import akka.pattern.ask

  object usernamePassword {
    def check(u: String, p: String)(implicit timeout: Timeout): Future[Boolean] =
      actorRef.ask(AuthenticationSrv.api.usernamePassword.Check(u, p)).mapTo[Boolean]
  }
}
