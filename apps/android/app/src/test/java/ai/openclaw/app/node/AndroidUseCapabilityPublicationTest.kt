package ai.openclaw.app.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUseCapabilityPublicationTest {
  @Test
  fun anUnpublishedChangeRemainsPendingUntilAConnectionRecordsIt() {
    val publication = AndroidUseCapabilityPublication()

    assertTrue(publication.needsRefresh(false))
    assertTrue(publication.needsRefresh(false))

    publication.record(false)
    assertFalse(publication.needsRefresh(false))
    assertTrue(publication.needsRefresh(true))
    assertTrue(publication.needsRefresh(true))

    publication.record(true)
    assertFalse(publication.needsRefresh(true))
    assertTrue(publication.needsRefresh(false))
  }
}
