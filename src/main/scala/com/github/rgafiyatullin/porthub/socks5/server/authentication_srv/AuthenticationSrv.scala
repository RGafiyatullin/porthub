package com.github.rgafiyatullin.porthub.socks5.server.authentication_srv

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props, Status}
import akka.util.Timeout
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.ActorStdReceive
import com.github.rgafiyatullin.porthub.socks5.security.audit.Event
import com.github.rgafiyatullin.porthub.socks5.server.Config
import com.github.rgafiyatullin.porthub.socks5.security.authentication.Identity
import com.github.rgafiyatullin.porthub.socks5.server.audit_srv.AuditSrv
import org.joda.time.DateTime

import scala.concurrent.Future
import scala.io.Source

object AuthenticationSrv {
  final case class Args(config: Config.Authentication, auditSrv: AuditSrv)

  def create(args: Args)(implicit arf: ActorRefFactory): AuthenticationSrv =
    AuthenticationSrv(arf.actorOf(Props(classOf[AuthenticationSrvActor], args), "authentication-srv"))

  final case class State(anonymousEnabled: Boolean, passwords: Map[(String, String), Identity] = Map.empty)

  final class AuthenticationSrvActor(args: Args) extends Actor with ActorStdReceive {

    val auditSrv = args.auditSrv

    def handleUsernamePasswordRequests(state: State): Receive = {
      case api.usernamePassword.IsAvailable =>
        sender() ! Status.Success(args.config.usernamePasswordOption.isDefined)

      case api.usernamePassword.Authenticate(downstreamSocketAddress, u, p) =>
        val identityOption = state.passwords.get(u,p)
        reportAuditEvent("user-pass", downstreamSocketAddress)(identityOption)

        sender() ! Status.Success(identityOption)
    }

    def handleAnonymousRequests(state: State): Receive = {
      case api.anonymous.IsAvailable =>
        sender() ! Status.Success(args.config.anonymousOption.isDefined)

      case api.anonymous.Authenticate(downstreamSocketAddress) =>
        val identityOption = if (args.config.anonymousOption.isDefined) Some(Identity.Anonymous) else None
        reportAuditEvent("anonymous", downstreamSocketAddress)(identityOption)

        sender() ! Status.Success(identityOption)
    }

    def reportAuditEvent(method: String, downstreamSocketAddress: InetSocketAddress)(identityOption: Option[Identity]): Unit =
      identityOption.fold {
        auditSrv.report(api.auditEvents.AuthenticationFailure(method, downstreamSocketAddress))
      } { identity =>
        auditSrv.report(api.auditEvents.AuthenticationSuccess(method, identity, downstreamSocketAddress))
      }

    def whenReady(state: State): Receive =
      handleUsernamePasswordRequests(state) orElse
        handleAnonymousRequests(state) orElse
        stdReceive.discard

    override def receive =
      initialize()

    def initializePasswords(): Map[(String, String), Identity] =
      args
        .config
        .usernamePasswordOption
        .fold(Map.empty[(String,String), Identity]) { config =>
          val source = Source.fromFile(config.file)
          val lines = source.getLines().toSeq
          lines.map { line =>
              val triplet = line.split("\\s+", 3)
              if (triplet.length == 3) {
                log.debug("Storing password auth for '{}'", triplet(2))
                Some((triplet(0), triplet(1)) -> new Identity.Identified {override val id = triplet(2)})
              } else
                None
            }
            .collect { case Some(defined) => defined }.toMap
        }

    def initialize(): Receive =
      whenReady(State(
        anonymousEnabled = args.config.anonymousOption.isDefined,
        passwords = initializePasswords()
      ))
  }

  object api {
    object auditEvents {
      final case class AuthenticationSuccess(
        method: String,
        identity: Identity,
        downstreamSocketAddr: InetSocketAddress,
        at: DateTime = DateTime.now())
          extends Event.WithIdentity
            with Event.WithDownstreamSocketAddr
      {
        override def toString =
          s"[$at] Auth success [identity: $identity; via-method: $method]"
      }

      final case class AuthenticationFailure(
        method: String,
        downstreamSocketAddr: InetSocketAddress,
        at: DateTime = DateTime.now())
          extends Event.WithDownstreamSocketAddr
      {
        override def toString =
          s"[$at] Auth failure [via-method: $method]"
      }
    }

    object usernamePassword {
      case object IsAvailable
      final case class Authenticate(
        downstreamSocketAddress: InetSocketAddress,
        u: String, p: String)
    }

    object anonymous {
      case object IsAvailable
      final case class Authenticate(
        downstreamSocketAddress: InetSocketAddress)
    }
  }
}

final case class AuthenticationSrv(actorRef: ActorRef) {
  import akka.pattern.ask

  object anonymous {
    def isAvailable()(implicit timeout: Timeout): Future[Boolean] =
      actorRef.ask(AuthenticationSrv.api.anonymous.IsAvailable).mapTo[Boolean]

    def authenticate(clientSocketAddress: InetSocketAddress)(implicit timeout: Timeout): Future[Option[Identity]] =
      actorRef.ask(
        AuthenticationSrv.api.anonymous.Authenticate(clientSocketAddress))
          .mapTo[Option[Identity]]
  }

  object usernamePassword {
    def isAvailable()(implicit timeout: Timeout): Future[Boolean] =
      actorRef.ask(AuthenticationSrv.api.usernamePassword.IsAvailable).mapTo[Boolean]

    def authenticate(
      clientSocketAddress: InetSocketAddress,
      u: String, p: String)(
      implicit timeout: Timeout)
    : Future[Option[Identity]] =
      actorRef.ask(
        AuthenticationSrv.api.usernamePassword.Authenticate(clientSocketAddress, u, p))
          .mapTo[Option[Identity]]
  }
}
