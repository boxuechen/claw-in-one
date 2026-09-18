package ai.openclaw.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNavigationTest {
  @Test fun selectionCoordinatesAttachmentsBeforeNavigationAndPersistsCapturedIdentity() {
    val h = Harness()
    h.feature.select(" topic ", " alice ")
    assertEquals(listOf("select: topic : alice ", "capture", "save"), h.events)
    assertEquals(listOf(ChatNavigationSelection("gateway", "topic", "alice")), h.saved)
  }

  @Test fun blankSelectionDoesNotTouchAnyOwner() {
    val h = Harness()
    h.feature.select("  ", null)
    assertTrue(h.events.isEmpty())
    assertTrue(h.saved.isEmpty())
  }

  @Test fun aLocalDraftOrUnavailableSelectionIsNeverPersisted() {
    val h = Harness()
    h.persistable = false
    h.feature.select("draft", "main")
    assertEquals(listOf("select:draft:main", "capture"), h.events)
    assertTrue(h.saved.isEmpty())
  }

  @Test fun newChatUsesTheExistingDraftOwnerWithoutSavingOrCreatingARuntimeSession() {
    val h = Harness()
    assertTrue(h.feature.newChat())
    assertEquals(listOf("draft"), h.events)
    assertTrue(h.saved.isEmpty())
    h.events.clear()
    h.canCreateDraft = false
    assertFalse(h.feature.newChat())
    assertEquals(listOf("draft"), h.events)
  }

  @Test fun retainedFeatureNeverLooksUpAReplacementOwner() {
    val original = Harness()
    val retained = original.feature
    val replacement = Harness()
    retained.select("original", "alice")
    assertTrue(replacement.events.isEmpty())
    replacement.feature.newChat()
    assertEquals("original", original.saved.single().sessionKey)
    assertTrue(replacement.saved.isEmpty())
  }

  private class Harness {
    val events = mutableListOf<String>()
    val saved = mutableListOf<ChatNavigationSelection>()
    var selection = ChatNavigationSelection("gateway", "main", null)
    var persistable = true
    var canCreateDraft = true
    val feature =
      ChatNavigation(
        selectSession = { key, owner ->
          events += "select:$key:$owner"
          selection = ChatNavigationSelection("gateway", key.trim(), owner?.trim())
        },
        newDraft = {
          events += "draft"
          canCreateDraft
        },
        captureSelection = {
          events += "capture"
          selection.takeIf { persistable }
        },
        saveSelection = {
          events += "save"
          saved += it
        },
      ).feature
  }
}
