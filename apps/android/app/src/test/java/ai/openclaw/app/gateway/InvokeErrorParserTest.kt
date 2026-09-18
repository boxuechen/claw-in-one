package ai.openclaw.app.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvokeErrorParserTest {
  @Test
  fun parseInvokeErrorMessage_parsesUppercaseCodePrefix() {
    val parsed = parseInvokeErrorMessage("MOBILE_UI_PERMISSION_REQUIRED: grant accessibility permission")
    assertEquals("MOBILE_UI_PERMISSION_REQUIRED", parsed.code)
    assertEquals("grant accessibility permission", parsed.message)
    assertTrue(parsed.hadExplicitCode)
    assertEquals("MOBILE_UI_PERMISSION_REQUIRED: grant accessibility permission", parsed.prefixedMessage)
  }

  @Test
  fun parseInvokeErrorMessage_parsesNumericCodePrefix() {
    val parsed = parseInvokeErrorMessage("API2_UNAVAILABLE: service not reachable")
    assertEquals("API2_UNAVAILABLE", parsed.code)
    assertEquals("service not reachable", parsed.message)
    assertTrue(parsed.hadExplicitCode)
  }

  @Test
  fun parseInvokeErrorMessage_rejectsNonCanonicalCodePrefix() {
    listOf(
      "IllegalStateException: boom",
      "2FAST: boom",
      "_PRIVATE: boom",
      "MOBILE-UI-PERMISSION: boom",
    ).forEach { raw ->
      val parsed = parseInvokeErrorMessage(raw)
      assertEquals("UNAVAILABLE", parsed.code)
      assertEquals(raw, parsed.message)
      assertFalse(parsed.hadExplicitCode)
    }
  }

  @Test
  fun parseInvokeErrorFromThrowable_usesFallbackWhenMessageMissing() {
    val parsed = parseInvokeErrorFromThrowable(IllegalStateException(), fallbackMessage = "fallback")
    assertEquals("UNAVAILABLE", parsed.code)
    assertEquals("fallback", parsed.message)
    assertFalse(parsed.hadExplicitCode)
  }
}
