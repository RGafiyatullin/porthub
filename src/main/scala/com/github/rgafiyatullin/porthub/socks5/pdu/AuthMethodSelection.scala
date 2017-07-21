package com.github.rgafiyatullin.porthub.socks5.pdu

object AuthMethodSelection {
  final case class AuthMethodSelectionRq(version: Byte, methods: Vector[Byte])
  final case class AuthMethodSelectionRs(version: Byte, method: Byte)

  object encoding {
    import scodec._
    import codecs._

    val authMethodSelectionRqCodec = (
        ( "version" | byte ) ::
        ( "methods" | vectorOfN(uint8, byte))
      ).as[AuthMethodSelectionRq]

    val authMethodSelectionRsCodec = (
        ( "version" | byte )  ::
        ( "method" | byte)
      ).as[AuthMethodSelectionRs]
  }

}
