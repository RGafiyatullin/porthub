
package com.github.rgafiyatullin.porthub

import akka.actor.ActorSystem
import com.github.rgafiyatullin.porthub.socks5.server.{Config => ServerConfig}
import com.github.rgafiyatullin.porthub.socks5.server.top_sup.TopSup
import com.typesafe.config.{ConfigFactory, Config}
import scala.concurrent.duration._

object Boot extends App {

  def withActorSystem(f: ActorSystem => Unit): Unit = {
    val actorSystem = ActorSystem("porthub")
    try f(actorSystem) catch { case _: Throwable => actorSystem.terminate() }
    ()
  }

  def configAuthenticationUsernamePassword(config: Config): Option[ServerConfig.Authentication.UsernamePassword] =
    if (config.getBoolean("porthub.authentication.username-password.enabled"))
      Some(ServerConfig.Authentication.UsernamePassword(config.getString("porthub.authentication.username-password.file")))
    else
      None

  def configAuthentication(config: Config): ServerConfig.Authentication =
    ServerConfig.Authentication(
      usernamePasswordOption = configAuthenticationUsernamePassword(config)
    )

  def configBind(config: Config): ServerConfig.Bind =
    ServerConfig.Bind(
      port = config.getInt("porthub.bind.port"),
      timeout = config.getDuration("porthub.bind.timeout").toMillis.milliseconds)

  def config(config: Config): ServerConfig =
    ServerConfig(
      bind = configBind(config),
      defaultOperationTimeout = config.getDuration("porthub.default-operation-timeout").toMillis.milliseconds,
      authentication = configAuthentication(config))

  withActorSystem { actorSystem =>
    val serverConfig = config(ConfigFactory.load())
    TopSup.create(TopSup.Args(serverConfig))(actorSystem)
    ()
  }

}
