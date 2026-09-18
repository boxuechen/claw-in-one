package ai.openclaw.app.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App-process lifetime: normal Activity recreation retains the selected Chat, but a recreated
 * process must select a fresh local draft even when Android restores the old saved UI state.
 * This owns presentation startup, not draft contents, Gateway Sessions, or permission state.
 */
internal class ChatLaunchState {
  var selected by mutableStateOf(false)
    private set

  fun selectDraft(select: () -> Boolean): Boolean {
    if (!selected) selected = select()
    return selected
  }
}
