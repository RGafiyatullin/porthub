package socks5.pdu

import org.scalatest.{FlatSpec, Matchers}
import com.github.rgafiyatullin.porthub.socks5.pdu.AuthMethodSelection
import com.github.rgafiyatullin.porthub.socks5.pdu.AuthMethodSelection.{AuthMethodSelectionRq, _}
import scodec.Attempt
import scodec.bits.BitVector

class AuthMethodSelectionTest extends FlatSpec with Matchers {
  val rqCodec = AuthMethodSelection.encoding.authMethodSelectionRqCodec
  val rsCodec = AuthMethodSelection.encoding.authMethodSelectionRsCodec
  val requestsIn = Vector(1,2,3,4,5).map(_.toByte).permutations.map(AuthMethodSelectionRq(5, _)).toSeq
  val responsesIn = (0 to 255).map(_.toByte).map(AuthMethodSelectionRs(5, _))

  "Requests" should "encode and decode separately" in {
    val attemptsToDecode = requestsIn.map(rqCodec.encode(_).flatMap(rqCodec.decode))
    requestsIn.zip(attemptsToDecode).foreach { case (request, attempt) =>
      attempt.isSuccessful should be (true)
      val Attempt.Successful(decodeResult) = attempt
      decodeResult.remainder.isEmpty should be (true)
      decodeResult.value should be (request)
    }
  }

  it should "encode and decode from a single bitstring (non-greedily)" in {
    val encoded = requestsIn.map(rqCodec.encode).map(_.require).foldLeft(BitVector.empty)(_ ++ _)
    val vecCodec = scodec.codecs.vector(rqCodec)
    val attemptToDecodeVector = vecCodec.decode(encoded)
    val Attempt.Successful(decodeResult) = attemptToDecodeVector
    decodeResult.remainder.isEmpty should be (true)
    decodeResult.value should be (requestsIn)
  }

  "Response" should "encode and decode" in {
    val attmemptsToDecode = responsesIn.map(rsCodec.encode(_).flatMap(rsCodec.decode))
    responsesIn.zip(attmemptsToDecode).foreach { case (response, attempt) =>
      attempt.isSuccessful should be (true)
      val Attempt.Successful(decodeResult) = attempt
      decodeResult.remainder.isEmpty should be (true)
      decodeResult.value should be (response)
    }
  }

  it should "encode and decode from a single bitstring (non-greedily)" in {
    val encoded = responsesIn.map(rsCodec.encode).map(_.require).foldLeft(BitVector.empty)(_ ++ _)
    val vecCodec = scodec.codecs.vector(rsCodec)
    val attemptToDecodeVector = vecCodec.decode(encoded)
    val Attempt.Successful(decodeResult) = attemptToDecodeVector
    decodeResult.remainder.isEmpty should be (true)
    decodeResult.value should be (responsesIn)
  }
}
