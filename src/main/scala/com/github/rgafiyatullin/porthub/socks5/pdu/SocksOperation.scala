package com.github.rgafiyatullin.porthub.socks5.pdu
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.Charset

import scodec.Codec
import shapeless.{:+:, CNil, Coproduct}

object SocksOperation {
  private val charset = Charset.forName("utf8")

  sealed trait Address {
    val bytes: Vector[Byte]
  }
  object Address {
    final case class Type1(o1: Byte, o2: Byte, o3: Byte, o4: Byte) extends Address {
      val bytes = Vector(o1, o2, o3, o4)

      val toInetAddress: InetAddress = InetAddress.getByAddress(Array(o1, o2, o3, o4))

    }
    final case class Type3(fqdnBytes: Vector[Byte]) extends Address {
      val bytes = fqdnBytes
      val toInetAddress: InetAddress = InetAddress.getByName(new String(fqdnBytes.toArray, charset))
    }
    final case class Type4(
      o1: Byte, o2: Byte, o3: Byte, o4: Byte,
      o5: Byte, o6: Byte, o7: Byte, o8: Byte,
      o9: Byte, o10: Byte, o11: Byte, o12: Byte,
      o13: Byte, o14: Byte, o15: Byte, o16: Byte
    ) extends Address {
      val bytes = Vector(
          o1, o2, o3, o4,
          o5, o6, o7, o8,
          o9, o10, o11, o12,
          o13, o14, o15, o16
        )

      val toInetAddress: InetAddress = InetAddress.getByAddress(
        Array(
          o1, o2, o3, o4,
          o5, o6, o7, o8,
          o9, o10, o11, o12,
          o13, o14, o15, o16))
    }
  }
  type AddressCoproduct = Address.Type1 :+: Address.Type3 :+: Address.Type4 :+: CNil
  val AddressCoproduct = Coproduct[AddressCoproduct]

  object SocksOperationRq {
    sealed trait Command
    final case class TcpConnect(inetSocketAddress: InetSocketAddress) extends Command
    final case class TcpBind(inetSocketAddress: InetSocketAddress) extends Command
    final case class UdpAssoc() extends Command
  }
  final case class SocksOperationRq(command: Byte, address: AddressCoproduct, port: Int) {
    import SocksOperationRq._

    val version: Byte = 5

    def inetSocketAddress: InetSocketAddress =
      (address.select[Address.Type1], address.select[Address.Type3], address.select[Address.Type4]) match {
        case (Some(type1), _, _) => new InetSocketAddress(type1.toInetAddress, port)

        case (_, Some(type3), _) => new InetSocketAddress(type3.toInetAddress, port)

        case (_, _, Some(type4)) => new InetSocketAddress(type4.toInetAddress, port)

        case _ => new InetSocketAddress(0)
      }

    def commandParsed: Option[Command] =
      command match {
        case 1 => Some(TcpConnect(inetSocketAddress))
        case 2 => Some(TcpBind(inetSocketAddress))
        case 3 => Some(UdpAssoc())
        case _ => None
      }
  }

  final case class SocksOperationRs(reply: Byte, address: AddressCoproduct, port: Int) { val version: Byte = 5 }

  object encoding {
    import scodec._
    import scodec.bits._
    import scodec.codecs._

    val addrType1Codec = (literals.constantByteVectorCodec(hex"01") :: byte :: byte :: byte :: byte).as[Address.Type1]
    val addrType3Codec = (literals.constantByteVectorCodec(hex"03") :: vectorOfN(uint8, byte)).as[Address.Type3]
    val addrType4Codec = (literals.constantByteVectorCodec(hex"04") ::
      byte :: byte :: byte :: byte ::
      byte :: byte :: byte :: byte ::
      byte :: byte :: byte :: byte ::
      byte :: byte :: byte :: byte).as[Address.Type4]

    val addrTypeCodec: Codec[AddressCoproduct] = (addrType1Codec :+: addrType3Codec :+: addrType4Codec).choice

    val socksOperationRqCodec = (
        ("version" | literals.constantByteVectorCodec(hex"05")) ::
        ("command" | byte) ::
        ("reserved" | literals.constantByteVectorCodec(hex"00")) ::
        ("address" | addrTypeCodec) ::
        ("port" | uint16)
      ).as[SocksOperationRq]

    val socksOperationRsCodec = (
        ("version" | literals.constantByteVectorCodec(hex"05")) ::
        ("reply" | byte) ::
        ("reserved" | literals.constantByteVectorCodec(hex"00")) ::
        ("address" | addrTypeCodec) ::
        ("port" | uint16)
      ).as[SocksOperationRs]

  }
}
