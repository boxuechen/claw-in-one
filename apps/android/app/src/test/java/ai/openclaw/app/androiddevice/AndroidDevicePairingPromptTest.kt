package ai.openclaw.app.androiddevice

import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDevicePairingPromptTest {
  @Test
  fun replyParserAcceptsOneEndpointAndOneSixDigitCode() {
    assertEquals(
      AndroidDevicePairingReply("10.0.0.2:41001", "654321"),
      parseAndroidDevicePairingReply("10.0.0.2:41001 654321"),
    )
    assertEquals(
      AndroidDevicePairingReply("[fd00::2]:41001", "654321"),
      parseAndroidDevicePairingReply("  [fd00::2]:41001\n654321  "),
    )
  }

  @Test
  fun replyParserRejectsAmbiguousOrInvalidInput() {
    assertNull(parseAndroidDevicePairingReply("10.0.0.2:41001"))
    assertNull(parseAndroidDevicePairingReply("10.0.0.2:0 654321"))
    assertNull(parseAndroidDevicePairingReply("10.0.0.2:70000 654321"))
    assertNull(parseAndroidDevicePairingReply("10.0.0.2:41001 12345"))
    assertNull(parseAndroidDevicePairingReply("10.0.0.2:41001 654321 extra"))
  }

  @Test
  fun replyIntentIsExplicitAndOwnsAStableIdentity() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val intent = androidDevicePairingReplyIntent(context)

    assertEquals(ComponentName(context, AndroidDevicePairingReplyReceiver::class.java), intent.component)
    assertEquals(context.packageName, intent.component?.packageName)
    assertTrue(isAndroidDevicePairingReplyIntent(intent))
    assertFalse(isAndroidDevicePairingReplyIntent(Intent(intent).setAction("unexpected")))
    assertFalse(isAndroidDevicePairingReplyIntent(Intent(intent).setData(null)))
  }
}
