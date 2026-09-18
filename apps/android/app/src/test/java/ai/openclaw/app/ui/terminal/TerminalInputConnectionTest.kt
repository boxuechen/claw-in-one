package ai.openclaw.app.ui.terminal

import ai.openclaw.app.ui.terminal.web.TerminalInputConnection
import ai.openclaw.app.ui.terminal.web.terminalKeyText
import android.view.KeyEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalInputConnectionTest {
  @Test fun composingChineseIsSentOnlyOnceWhenCommitted() {
    val sent = mutableListOf<String>()
    val connection = TerminalInputConnection(View(RuntimeEnvironment.getApplication()), sent::add, { false })
    connection.setComposingText("zhong", 1)
    connection.setComposingText("中文", 1)
    assertTrue(sent.isEmpty())
    connection.commitText("中文", 1)
    connection.finishComposingText()
    assertEquals(listOf("中文"), sent)
    connection.commitText("echo ready", 1)
    connection.performEditorAction(0)
    connection.deleteSurroundingText(1, 0)
    assertEquals(listOf("中文", "echo ready", "\r", "\u007f"), sent)
  }

  @Test fun finishingCompositionAndHardwareKeysDoNotInventCommands() {
    val sent = mutableListOf<String>()
    val connection = TerminalInputConnection(View(RuntimeEnvironment.getApplication()), sent::add, { false })
    connection.setComposingText("test", 1)
    connection.finishComposingText()
    connection.finishComposingText()
    assertEquals(listOf("test"), sent)
    assertNull(terminalKeyText(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
    assertEquals("\r", terminalKeyText(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
    assertEquals("\u001b[A", terminalKeyText(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)))
    assertEquals("\u0003", terminalKeyText(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON)))
  }

  @Test fun multilineAndControlCharacterPasteRequiresConfirmation() {
    assertFalse(terminalPasteNeedsConfirmation("echo hello"))
    assertTrue(terminalPasteNeedsConfirmation("echo hello\n"))
    assertTrue(terminalPasteNeedsConfirmation("\u001b[201~echo hello\r"))
  }
}
