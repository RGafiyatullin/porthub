package socks5.pdu

import java.nio.charset.Charset

import com.github.rgafiyatullin.porthub.socks5.pdu.SocksOperation
import com.github.rgafiyatullin.porthub.socks5.pdu.SocksOperation.{Address, AddressCoproduct, SocksOperationRq, SocksOperationRs}
import org.scalatest.{FlatSpec, Matchers}
import scodec.Attempt

class SocksOperationTest extends FlatSpec with Matchers {
  val charset = Charset.forName("utf8")
  val rqCodec = SocksOperation.encoding.socksOperationRqCodec
  val rsCodec = SocksOperation.encoding.socksOperationRsCodec

  val requestsIn = Seq(
    SocksOperationRq(5, 1, AddressCoproduct(Address.Type3("example.com".getBytes(charset).toVector)), 80),
    SocksOperationRq(5, 2, AddressCoproduct(Address.Type1(1,2,3,4)), 80),
    SocksOperationRq(5, 3, AddressCoproduct(Address.Type4(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16)), 80)
  )

  val responsesIn = Seq(
    SocksOperationRs(5, 1, AddressCoproduct(Address.Type3("example.com".getBytes(charset).toVector)), 80),
    SocksOperationRs(5, 2, AddressCoproduct(Address.Type1(1,2,3,4)), 80),
    SocksOperationRs(5, 3, AddressCoproduct(Address.Type4(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16)), 80)
  )

  "requests" should "encode and decode" in {
    val attemptsToDecode = requestsIn.map(rqCodec.encode(_).flatMap(rqCodec.decode))
    requestsIn.zip(attemptsToDecode).foreach { case (request, attempt) =>
        attempt.isSuccessful should be (true)
        val Attempt.Successful(decodeResult) = attempt
        decodeResult.remainder.isEmpty should be (true)
        decodeResult.value should be (request)
      }
  }

  "responses" should "encode and decode" in {
    val attemptsToDecode = responsesIn.map(rsCodec.encode(_).flatMap(rsCodec.decode))
    responsesIn.zip(attemptsToDecode).foreach { case (response, attempt) =>
      attempt.isSuccessful should be (true)
      val Attempt.Successful(decodeResult) = attempt
      decodeResult.remainder.isEmpty should be (true)
      decodeResult.value should be (response)
    }
  }
}
