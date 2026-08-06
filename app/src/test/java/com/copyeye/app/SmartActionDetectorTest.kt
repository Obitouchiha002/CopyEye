package com.copyeye.app

import com.copyeye.app.ocr.SmartAction
import com.copyeye.app.ocr.SmartActionDetector
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Smart actions are a convenience layered on top of copying, so the tests care as much about what
 * is *not* offered as about what is. A wrong "Call" button in front of someone who wanted to copy a
 * price is worse than no button at all.
 */
class SmartActionDetectorTest {

    @Test
    fun `a bare domain becomes an https link`() {
        val actions = SmartActionDetector.detect("visit www.example.com today")

        assertThat(actions).contains(SmartAction.OpenUrl("https://www.example.com"))
    }

    @Test
    fun `an explicit scheme is left alone`() {
        val actions = SmartActionDetector.detect("http://example.org/page?a=1")

        assertThat(actions).contains(SmartAction.OpenUrl("http://example.org/page?a=1"))
    }

    @Test
    fun `an email address is not also offered as a link`() {
        val actions = SmartActionDetector.detect("write to me at test.user@example.com")

        assertThat(actions.filterIsInstance<SmartAction.Email>()).hasSize(1)
        assertThat(actions.filterIsInstance<SmartAction.OpenUrl>()).isEmpty()
    }

    @Test
    fun `an Indian mobile number is offered as a call`() {
        val actions = SmartActionDetector.detect("call +91 98765 43210 for details")

        assertThat(actions.filterIsInstance<SmartAction.Call>()).hasSize(1)
    }

    @Test
    fun `a ten digit number without a country code is still a call`() {
        val actions = SmartActionDetector.detect("9876543210")

        assertThat(actions.filterIsInstance<SmartAction.Call>()).hasSize(1)
    }

    @Test
    fun `a year is not a phone number`() {
        assertThat(SmartActionDetector.detect("Released in 2024")).isEmpty()
    }

    @Test
    fun `a price is not a phone number`() {
        assertThat(SmartActionDetector.detect("₹ 1,299 only")).isEmpty()
    }

    @Test
    fun `a six digit OTP is not a phone number`() {
        assertThat(SmartActionDetector.detect("Your code is 448210")).isEmpty()
    }

    @Test
    fun `plain prose offers nothing`() {
        assertThat(SmartActionDetector.detect("यह एक साधारण वाक्य है।")).isEmpty()
    }

    @Test
    fun `an address with a PIN code offers maps`() {
        val actions = SmartActionDetector.detect("12 MG Road, Bengaluru, Karnataka 560001")

        assertThat(actions.filterIsInstance<SmartAction.Map>()).hasSize(1)
    }

    @Test
    fun `empty and oversized input are ignored`() {
        assertThat(SmartActionDetector.detect("")).isEmpty()
        assertThat(SmartActionDetector.detect("a".repeat(10_000))).isEmpty()
    }

    @Test
    fun `duplicate matches appear once`() {
        val actions = SmartActionDetector.detect("a@b.com and a@b.com again")

        assertThat(actions.filterIsInstance<SmartAction.Email>()).hasSize(1)
    }
}
