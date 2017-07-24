package com.github.rgafiyatullin.porthub.socks5.server

import akka.util.Timeout
import com.github.rgafiyatullin.porthub.socks5.server.Config.Authentication.UsernamePassword

import scala.concurrent.duration._

object Config {
  object Authentication {
    final case class UsernamePassword(file: String)
  }

  final case class Authentication(usernamePasswordOption: Option[UsernamePassword] = None)

  final case class Bind(port: Int = 1080, timeout: FiniteDuration = 5.seconds)
}

final case class Config(
  bind: Config.Bind = Config.Bind(),
  defaultOperationTimeout: Timeout = 5.seconds,
  authentication: Config.Authentication = Config.Authentication())
