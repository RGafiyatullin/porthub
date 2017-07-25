package com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_authenticating

import java.net.InetSocketAddress

import akka.actor.Actor.Receive
import akka.actor.ActorRef
import akka.io.Tcp
import com.github.rgafiyatullin.porthub.socks5.pdu.AuthMethodSelection
import com.github.rgafiyatullin.porthub.socks5.pdu.AuthMethodSelection.AuthMethodSelectionRs
import com.github.rgafiyatullin.porthub.socks5.security.audit.Event
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.ConnectionSrv
import com.github.rgafiyatullin.porthub.socks5.security.authentication.Identity
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_authenticating.WhenAuthenticating.AuthenticatorProvider
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_authenticating.authentication.{NoAuthRequiredAuthenticator, UsernamePasswordAuthenticator}
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.when_selecting_mode.WhenSelectingMode
import com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states.{ActorState, ActorStateTcpUtil}
import org.joda.time.DateTime

import scala.concurrent.Future
import scala.util.Success

object WhenAuthenticating {
  trait AuthenticatorProvider {
    val authMethodId: Byte

    def create(onSuccess: Authenticator.OnSuccess): Authenticator
  }
  object Authenticator {
    type OnSuccess = (ConnectionSrv.State, Identity, ActorStateTcpUtil.State) => Receive
  }

  trait Authenticator {
    def receive(tcpUtilState: ActorStateTcpUtil.State): Receive
  }

  object auditEvents {
    final case class NoSuitableAuthMethodFound(
      client: Set[Byte],
      server: Set[Byte],
      downstreamSocketAddr: InetSocketAddress,
      connectionActorRef: ActorRef,
      at: DateTime = DateTime.now())
        extends Event.WithConnection with Event.WithDownstreamSocketAddr
    {
      override def toString =
        s"[$at] No suitable auth-method found [client: [${client.mkString(",")}]; " +
          s"server: [${server.mkString(",")}]; via: $connectionActorRef; from: $downstreamSocketAddr]"
    }

    final case class AuthMethodChosen(
      method: Byte,
      downstreamSocketAddr: InetSocketAddress,
      connectionActorRef: ActorRef,
      at: DateTime = DateTime.now()) extends Event.WithConnection with Event.WithDownstreamSocketAddr
    {
      override def toString =
        s"[$at] Proceeding with auth-method $method [via: $connectionActorRef; from: $downstreamSocketAddr]"
    }
  }
}

final case class WhenAuthenticating(
    state: ConnectionSrv.State)
  extends ActorState[ConnectionSrv.ConnectionSrvActor]
    with ActorStateTcpUtil[ConnectionSrv.ConnectionSrvActor]
{
  val auditSrv = state.auditSrv
  val authenticationSrv = state.authenticationSrv
  val actor = state.actor
  val stdReceive = actor.stdReceive
  val future = actor.future
  val log = actor.log
  implicit val executionContext = context.dispatcher

  val authMethodSelectionRqCodec = AuthMethodSelection.encoding.authMethodSelectionRqCodec
  val authMethodSelectionRsCodec = AuthMethodSelection.encoding.authMethodSelectionRsCodec

  def authenticatorOptionFuture(isEnabledFuture: Future[Boolean], provider: => AuthenticatorProvider): Future[Option[AuthenticatorProvider]] =
    for { isEnabled <- isEnabledFuture }
      yield if (isEnabled) Some(provider) else None

  val noAuthRequiredAuthenticatorOptionFuture =
    authenticatorOptionFuture(
      authenticationSrv.anonymous.isAvailable()(state.operationTimeout),
      NoAuthRequiredAuthenticator.Provider(state))


  val usernamePasswordAuthenticatorOptionFuture =
    authenticatorOptionFuture(
      authenticationSrv.usernamePassword.isAvailable()(state.operationTimeout),
      UsernamePasswordAuthenticator.Provider(state))

  val authenticatorProvidersFuture =
    for { options <- Future.sequence(Seq(noAuthRequiredAuthenticatorOptionFuture, usernamePasswordAuthenticatorOptionFuture)) }
      yield options.collect { case Some(defined) => defined }.map(p => p.authMethodId -> p).toMap


  def chooseAuthMethod(authenticatorProviders: Map[Byte, AuthenticatorProvider], rq: AuthMethodSelection.AuthMethodSelectionRq): Byte = {
    val availableAuthIds = authenticatorProviders.keys.toSet
    val preferredAuthIds = rq.methods.toSet
    val commonIds = availableAuthIds intersect preferredAuthIds
    log.debug(
      "Choosing auth method. Server: [{}], Client: [{}], Intersect: [{}]",
      availableAuthIds.mkString(","), preferredAuthIds.mkString(","), commonIds.mkString(","))

    if (commonIds.isEmpty) {
      auditSrv.report(
        WhenAuthenticating.auditEvents.NoSuitableAuthMethodFound(
          preferredAuthIds, availableAuthIds, state.downstreamSocketAddress, self))

      255.toByte
    } else {
      val methodIdx = commonIds.max
      auditSrv.report(
        WhenAuthenticating.auditEvents.AuthMethodChosen(methodIdx, state.downstreamSocketAddress, self))

      methodIdx
    }
  }

  final class WithAuthenticationProviders(authenticatorProviders: Map[Byte, AuthenticatorProvider]) {
    def handleRq(tcpUtilState: ActorStateTcpUtil.State, rq: AuthMethodSelection.AuthMethodSelectionRq): Receive = {
      log.debug("Client talks SOCKSv{} and supports the following auth-methods: [{}]", rq.version, rq.methods.mkString(","))
      val authMethodIdChosen = chooseAuthMethod(authenticatorProviders, rq)
      log.debug("Auth method chosen: {}", authMethodIdChosen)
      val response = AuthMethodSelectionRs(authMethodIdChosen)
      tcpUtil.write(state.downstreamTcp, authMethodSelectionRsCodec, response)

      if (authMethodIdChosen != 255) {
        val onAuthSuccess = WhenSelectingMode(_: ConnectionSrv.State, _: Identity).receive(_: ActorStateTcpUtil.State)
        val authenticator = authenticatorProviders(authMethodIdChosen).create(onAuthSuccess)
        authenticator.receive(tcpUtilState)
      } else {
        log.debug("No suitable auth-method found")
        context stop self
        stdReceive.discard
      }
    }

    def handleRqDecodeErr(tcpUtilState: ActorStateTcpUtil.State, decodeError: scodec.Err): Receive = {
      log.debug("Failed to decode PDU. Shutting down with no response.")

      context stop self
      stdReceive.discard
    }

    def whenExpectingRq(tcpUtilState: ActorStateTcpUtil.State): Receive =
      tcpUtil.handleTcpReceived(
          state.downstreamTcp,
          authMethodSelectionRqCodec,
          tcpUtilState
        )(whenExpectingRq)(handleRq)(handleRqDecodeErr) orElse
          stdReceive.discard
  }



  def receive(): Receive = {
    log.debug("initalizing...")
    state.downstreamTcp ! Tcp.Register(self)

    future.handle(authenticatorProvidersFuture) {
      case Success(authenticationProviders) =>
        new WithAuthenticationProviders(authenticationProviders).whenExpectingRq(ActorStateTcpUtil.State.empty)
    }
  }

}
