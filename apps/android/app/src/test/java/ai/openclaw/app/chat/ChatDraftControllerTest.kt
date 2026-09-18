package ai.openclaw.app.chat

import ai.openclaw.app.permissions.SessionPermissionConnection
import ai.openclaw.app.permissions.SessionPermissionMode
import ai.openclaw.app.permissions.SessionPermissionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDraftControllerTest {
  private val drafts = ChatDraftController()

  @Test
  fun newDraftsHaveIndependentIdentitiesAndNeverInheritFull() {
    val first = drafts.newDraft("phone", "main")
    assertTrue(drafts.choose(first.intent.target, SessionPermissionMode.Full))
    val second = drafts.newDraft("phone", "main")
    assertEquals(SessionPermissionMode.Standard, second.intent.mode)
    assertNotEquals(first.intent.target.key, second.intent.target.key)
    assertNotEquals(first.intent.idempotencyKey, second.intent.idempotencyKey)
    assertNull(drafts.find("another-phone", first.intent.target.key))
    assertTrue(second.editable)
  }

  @Test
  fun firstAttemptSealsPermissionsAndModelWithoutOwningMessageOrAttachments() {
    val target = drafts.newDraft("phone", "main").intent.target
    drafts.choose(target, SessionPermissionMode.Full)
    drafts.selectModel(target, "deepseek/deepseek-chat")
    drafts.selectThinking(target, "off")
    val intent = checkNotNull(drafts.beginCreation(target))
    assertEquals(SessionPermissionMode.Full, intent.mode)
    assertEquals("deepseek/deepseek-chat", intent.modelRef)
    assertEquals("off", intent.thinkingLevel)
    assertNull(drafts.beginCreation(target))
    assertFalse(drafts.choose(target, SessionPermissionMode.Standard))
    assertFalse(drafts.selectModel(target, null))
    assertFalse(drafts.selectThinking(target, "high"))
    drafts.unconfirmed(intent)
    assertEquals(
      ChatDraftPhase.Unconfirmed,
      drafts.states.value
        .getValue(target)
        .phase,
    )
    assertEquals(intent, drafts.beginCreation(target))
  }

  @Test
  fun canonicalRecoveryPreservesTheComposerOwnerButNeverBeginsAnotherCreation() {
    val target = drafts.newDraft("phone", "ops").intent.target
    val intent = checkNotNull(drafts.beginCreation(target))
    drafts.unconfirmed(intent)
    val connection = SessionPermissionConnection("phone", 2, 1, setOf("operator.admin"), emptySet())
    val ref = SessionPermissionRef(target, connection, "session-1")
    assertFalse(drafts.confirm(intent.copy(idempotencyKey = "foreign"), ref))
    assertFalse(drafts.confirm(intent, ref.copy(target = target.copy(gatewayId = "other"))))
    assertTrue(drafts.confirm(intent, ref))
    drafts.unconfirmed(intent)
    val created = drafts.states.value.getValue(target)
    assertEquals(ChatDraftPhase.Created, created.phase)
    assertEquals(target, created.intent.target)
    assertEquals(ref, created.createdRef)
    assertNull(drafts.beginCreation(target))
    assertFalse(drafts.choose(target, SessionPermissionMode.Full))
  }
}
