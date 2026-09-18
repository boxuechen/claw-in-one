package ai.openclaw.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRunActivityStoreTest {
  @Test
  fun activityRequiresRunIdentityReplacesItemsAndRejectsOlderUpdates() {
    val store = ChatRunActivityStore()

    assertFalse(store.accept(segment(runId = null, itemId = "missing", text = "ignored", sequence = 1)))
    assertTrue(store.accept(segment("run-1", "item-1", "first", 2)))
    assertFalse(store.accept(segment("run-1", "item-1", "stale", 1)))
    assertTrue(store.accept(segment("run-1", "item-1", "updated", 3)))

    assertEquals("run-1", store.activity.value?.runId)
    assertEquals(
      listOf("updated"),
      store.activity.value
        ?.commentary
        ?.map { it.text },
    )
  }

  @Test
  fun activityIsBoundedAndRestoreAcceptsOnlyTheAuthoritativeRun() {
    val store = ChatRunActivityStore(commentaryLimit = 3)
    (1..5).forEach { index ->
      store.accept(segment("run-1", "item-$index", "update $index", index.toLong()))
    }
    assertEquals(
      listOf("update 3", "update 4", "update 5"),
      store.activity.value
        ?.commentary
        ?.map { it.text },
    )

    store.restore(
      runId = "run-2",
      commentary =
        listOf(
          segment("run-1", "wrong", "wrong run", 1),
          segment("run-2", "right", "restored", 2),
        ),
    )
    assertEquals("run-2", store.activity.value?.runId)
    assertEquals(
      listOf("restored"),
      store.activity.value
        ?.commentary
        ?.map { it.text },
    )

    store.clear("run-1")
    assertEquals("run-2", store.activity.value?.runId)
    store.clear("run-2")
    assertNull(store.activity.value)
  }

  private fun segment(
    runId: String?,
    itemId: String,
    text: String,
    sequence: Long,
  ) = ChatCommentarySegment(runId, itemId, text, timestampMs = sequence, sequence = sequence)
}
