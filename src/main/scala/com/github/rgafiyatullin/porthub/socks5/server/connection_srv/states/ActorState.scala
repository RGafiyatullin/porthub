package com.github.rgafiyatullin.porthub.socks5.server.connection_srv.states

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorContext, ActorRef}
import akka.event.LoggingReceive

trait ActorState[ActorType <: Actor] {
  val actor: ActorType

  implicit val context: ActorContext = actor.context
  implicit val self: ActorRef = context.self
  val sender = context.sender _

//  object actorStateContext {
//    def become(behaviour: Receive): Unit = context become LoggingReceive(behaviour)(context)
//  }
}
