package ai.openclaw.app.ui.terminal.web

import android.text.Editable
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection

/** IME composition stays local until committed; no command queue or terminal history buffer. */
internal class TerminalInputConnection(
  view: View,
  private val send: (String) -> Unit,
  private val key: (KeyEvent) -> Boolean,
) : BaseInputConnection(view, true) {
  private val composing = SpannableStringBuilder()

  override fun getEditable(): Editable = composing

  override fun commitText(
    text: CharSequence?,
    newCursorPosition: Int,
  ): Boolean {
    text?.toString()?.takeIf { it.isNotEmpty() }?.let(send)
    composing.clear()
    removeComposingSpans(composing)
    return true
  }

  override fun finishComposingText(): Boolean {
    if (composing.isNotEmpty()) send(composing.toString())
    composing.clear()
    return super.finishComposingText()
  }

  override fun deleteSurroundingText(
    beforeLength: Int,
    afterLength: Int,
  ): Boolean {
    if (composing.isNotEmpty()) return super.deleteSurroundingText(beforeLength, afterLength)
    if (beforeLength > 0) send("\u007f".repeat(beforeLength.coerceAtMost(100)))
    if (afterLength > 0) send("\u001b[3~".repeat(afterLength.coerceAtMost(100)))
    return true
  }

  override fun sendKeyEvent(event: KeyEvent): Boolean = key(event)

  override fun performEditorAction(actionCode: Int): Boolean {
    send("\r")
    return true
  }
}

internal fun terminalKeyText(event: KeyEvent): String? =
  when (event.keyCode) {
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
    KeyEvent.KEYCODE_DEL -> "\u007f"
    KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
    KeyEvent.KEYCODE_TAB -> "\t"
    KeyEvent.KEYCODE_ESCAPE -> "\u001b"
    KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
    KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
    KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
    KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H"
    KeyEvent.KEYCODE_MOVE_END -> "\u001b[F"
    else -> {
      val code = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
      when {
        code == 0 -> null
        event.isCtrlPressed && code in 64..127 -> (code and 31).toChar().toString()
        event.isAltPressed -> "\u001b" + String(Character.toChars(code))
        else -> String(Character.toChars(code))
      }
    }
  }
