package ai.openclaw.app.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VScreenTargetRegistryTest {
  @Test
  fun geometryKeepsRevisionWhileWorkloadAndTargetReplacementRetireExactly() {
    val retired = mutableListOf<VScreenTarget>()
    val registry = VScreenTargetRegistry { retired += it }
    val first = registry.publish(candidate(ATTACHMENT_A, workload = null))
    val resized = registry.publish(candidate(ATTACHMENT_A, workload = null).copy(width = 1280))
    assertEquals(first.revision, resized.revision)
    val workload = registry.publish(candidate(ATTACHMENT_A, workload = WORKLOAD))
    assertTrue(workload.revision > resized.revision)
    assertEquals(listOf(resized), retired)

    registry.clear(ATTACHMENT_A, targetGeneration = 2)
    assertEquals(workload, registry.current())
    registry.clear(ATTACHMENT_A, targetGeneration = 1)
    assertNull(registry.current())
    assertEquals(listOf(resized, workload), retired)
  }

  private fun candidate(
    attachment: String,
    workload: String?,
  ) = VScreenTargetCandidate(
    producerId = "claw-in-one-android-vscreen-producer",
    workloadRequestId = workload,
    attachmentId = attachment,
    targetRef = "target:one",
    targetGeneration = 1,
    sourceGeneration = 1,
    displayId = 7,
    width = 720,
    height = 1560,
    dpi = 320,
  )

  private companion object {
    const val ATTACHMENT_A = "82345678-1234-4123-8123-123456789abc"
    const val WORKLOAD = "92345678-1234-4123-8123-123456789abc"
  }
}
