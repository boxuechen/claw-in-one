package ai.openclaw.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLaunchStateTest {
  @Test fun activityRecreationKeepsTheProcessOwnedSelectionWithoutStartingAnotherDraft() {
    val retained = ChatLaunchState()
    var selections = 0
    assertTrue(
      retained.selectDraft {
        selections++
        true
      },
    )
    assertTrue(
      retained.selectDraft {
        selections++
        true
      },
    )
    assertEquals(1, selections)
  }

  @Test fun aNewProcessCannotInheritThePreviousProcessLaunchFlag() {
    val previous = ChatLaunchState()
    previous.selectDraft { true }
    val recreated = ChatLaunchState()
    assertFalse(recreated.selected)
    assertTrue(recreated.selectDraft { true })
    assertTrue(recreated.selected)
  }

  @Test fun unavailableRoutingDoesNotClaimSuccessfulStartup() {
    val state = ChatLaunchState()
    assertFalse(state.selectDraft { false })
    assertFalse(state.selected)
    assertTrue(state.selectDraft { true })
  }
}
