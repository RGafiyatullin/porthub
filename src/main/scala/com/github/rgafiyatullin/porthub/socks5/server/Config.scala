package com.github.rgafiyatullin.porthub.socks5.server

import akka.util.Timeout

import scala.concurrent.duration._

final case class Config(
  bindPort: Int = 1080,
  bindTimeout: FiniteDuration = 5.seconds,
  defaultOperationTImeout: Timeout = 5.seconds)
