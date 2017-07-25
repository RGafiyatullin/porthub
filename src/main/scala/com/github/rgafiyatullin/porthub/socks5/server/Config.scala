package com.github.rgafiyatullin.porthub.socks5.server

import akka.util.Timeout
import scala.concurrent.duration._

object Config {
  object Authentication {
    final case class Anonymous()
    final case class UsernamePassword(file: String)
  }

  final case class Authentication(
    anonymousOption: Option[Authentication.Anonymous] = None,
    usernamePasswordOption: Option[Authentication.UsernamePassword] = None)
  final case class Audit()

  final case class Bind(port: Int = 1080, timeout: FiniteDuration = 5.seconds)
}

final case class Config(
  bind: Config.Bind = Config.Bind(),
  defaultOperationTimeout: Timeout = 5.seconds,
  audit: Config.Audit = Config.Audit(),
  authentication: Config.Authentication = Config.Authentication())
