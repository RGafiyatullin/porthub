package com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorContext, ActorRef}
import akka.event.LoggingReceive

trait ActorState[ActorType <: Actor] {
  val actor: ActorType

  implicit lazy val context: ActorContext = actor.context
  implicit lazy val self: ActorRef = context.self
  val sender = context.sender _
}
