
package com.github.rgafiyatullin.porthub

import akka.actor.ActorSystem
import com.github.rgafiyatullin.porthub.socks5.server.Config
import com.github.rgafiyatullin.porthub.socks5.server.top_sup.TopSup

object Boot extends App {

  def withActorSystem(f: ActorSystem => Unit): Unit = {
    val actorSystem = ActorSystem("porthub")
    try f(actorSystem) catch { case _: Throwable => actorSystem.terminate() }
    ()
  }

  withActorSystem { actorSystem =>
    val config = Config()
    TopSup.create(TopSup.Args(config))(actorSystem)
    ()
  }

}
