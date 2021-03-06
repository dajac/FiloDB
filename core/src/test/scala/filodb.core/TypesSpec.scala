package filodb.core

import scodec.bits._
import org.scalatest.{FunSpec, Matchers}

class TypesSpec extends FunSpec with Matchers {
  import Types._
  import Ordered._   // enables a < b

  describe("ByteVectorOrdering") {
    it("should compare by length if contents equal") {
      hex"0001" should equal (hex"0001")
      hex"0001" should be > hex"00"
      hex"0001" should be < hex"000102"
    }

    it("should compare by unsigned bytes") {
      hex"00" should be < hex"ff"
      hex"00ff" should be < hex"0100"
      hex"0080" should be > hex"007f"
    }
  }
}