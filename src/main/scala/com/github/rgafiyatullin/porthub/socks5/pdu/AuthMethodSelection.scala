package com.github.rgafiyatullin.porthub.socks5.pdu

object AuthMethodSelection {
  final case class AuthMethodSelectionRq(methods: Vector[Byte]) { val version: Byte = 5 }
  final case class AuthMethodSelectionRs(method: Byte) { val version: Byte = 5 }

  object encoding {
    import scodec._
    import scodec.codecs._
    import scodec.bits._

    val authMethodSelectionRqCodec = (
        ( "version" | literals.constantByteVectorCodec(hex"05") ) ::
        ( "methods" | vectorOfN(uint8, byte))
      ).as[AuthMethodSelectionRq]

    val authMethodSelectionRsCodec = (
        ( "version" | literals.constantByteVectorCodec(hex"05") )  ::
        ( "method" | byte)
      ).as[AuthMethodSelectionRs]
  }

}
