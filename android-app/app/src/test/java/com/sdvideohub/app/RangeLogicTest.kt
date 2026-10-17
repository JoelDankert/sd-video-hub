package com.sdvideohub.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeLogicTest {
    @Test fun parsesOpenClosedAndSuffixRanges() {
        assertNull(RangeLogic.parse(null, 100))
        assertEquals(ByteRange(10, 99), RangeLogic.parse("bytes=10-", 100))
        assertEquals(ByteRange(10, 20), RangeLogic.parse("bytes=10-20", 100))
        assertEquals(ByteRange(90, 99), RangeLogic.parse("bytes=-10", 100))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnsatisfiableRange() { RangeLogic.parse("bytes=100-", 100) }
    @Test(expected = IllegalArgumentException::class) fun rejectsMultipleRanges() { RangeLogic.parse("bytes=0-1,3-4", 100) }
}
