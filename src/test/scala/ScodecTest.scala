import org.scalatest.{FlatSpec, Matchers}

class ScodecTest extends FlatSpec with Matchers {
  import scodec._
  import scodec.bits._
  import codecs._

  "Two byte codec" should "work" in {
    val codec = uint8 ~ uint8

    val binary = hex"0d0a".bits
    binary.toByteArray should be (Array(13.toByte, 10.toByte))

    val decodeAttempt = codec.decode(binary)
    decodeAttempt.isSuccessful should be (true)

    val _ =
      for {
        result <- decodeAttempt
      } yield {
        result.remainder.isEmpty should be (true)
        result.value should be ((13.toByte, 10.toByte))
      }
  }

  "Len-prefixed string codec" should "work" in {
    val codec = vectorOfN(uint8, byte) ~ byte

    val value = (Set(1.toByte, 2.toByte).toVector, 3.toByte)

    val encAttempt = codec.encode(value)
    encAttempt.isSuccessful should be (true)
    val Attempt.Successful(binary) = encAttempt

    val decAttempt = codec.decode(binary)
    decAttempt.isSuccessful should be (true)
    val Attempt.Successful(decodeResult) = decAttempt
    decodeResult.remainder.isEmpty should be (true)
    decodeResult.value should be (value)
  }


  "Buffer underrun" should "happen" in {
    val codec = vectorOfN(uint8, byte) ~ byte
    val value = (Set(1.toByte, 2.toByte).toVector, 3.toByte)
    val encAttempt = codec.encode(value)
    encAttempt.isSuccessful should be (true)
    val Attempt.Successful(bits) = encAttempt
    val bytes = bits.bytes
    val bytesCut = bytes.slice(0, bytes.length - 1)
    val decAttempt = codec.decode(bytesCut.bits)
    val Attempt.Failure(reason) = decAttempt
    reason shouldBe a[Err.InsufficientBits]
  }
}
