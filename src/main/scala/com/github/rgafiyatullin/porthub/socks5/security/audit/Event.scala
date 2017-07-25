package com.github.rgafiyatullin.porthub.socks5.security.audit

import java.net.InetSocketAddress

import akka.actor.ActorRef
import com.github.rgafiyatullin.porthub.socks5.security.authentication.Identity
import org.joda.time.DateTime

trait Event {
  val at: DateTime
}

object Event {
  trait WithIdentity extends Event {
    val identity: Identity
  }

  trait WithConnection extends Event {
    val connectionActorRef: ActorRef
  }

  trait WithDownstreamSocketAddr extends Event {
    val downstreamSocketAddr: InetSocketAddress
  }

  trait WithUpstreamSocketAddr extends Event {
    val upstreamSocketAddr: InetSocketAddress
  }
}
