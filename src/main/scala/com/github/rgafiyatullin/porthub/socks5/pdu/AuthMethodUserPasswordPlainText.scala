package com.github.rgafiyatullin.porthub.socks5.pdu

object AuthMethodUserPasswordPlainText {
  final case class AuthMethodUserPasswordPlainTextRq(uname: Vector[Byte], password: Vector[Byte])
  final case class AuthMethodUserPasswordPlainTextRs(status: Byte)

  object encoding {
    import scodec._
    import scodec.codecs._
    import scodec.bits._

    val authMethodUserPasswordPlainTextRqCodec = (
        ("version" | literals.constantByteVectorCodec(hex"01")) ::
        ("uname" | vectorOfN(uint8, byte)) ::
        ("password" | vectorOfN(uint8, byte))
      ).as[AuthMethodUserPasswordPlainTextRq]

    val authMethodUserPasswordPlainTextRsCodec = (
        ("version" | literals.constantByteVectorCodec(hex"01")) ::
        ("status" | byte)
      ).as[AuthMethodUserPasswordPlainTextRs]
  }
}
