package com.github.rgafiyatullin.porthub.socks5.security.authentication


sealed trait Identity {}

object Identity {
  case object Anonymous extends Identity {
    override def toString = "Identity.Anonymous"
  }
  trait Identified extends Identity {
    val id: String

    override def toString = s"Identity.Identified($id)"
  }
}
