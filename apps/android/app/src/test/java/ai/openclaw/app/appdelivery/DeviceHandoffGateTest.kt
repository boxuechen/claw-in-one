package ai.openclaw.app.appdelivery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHandoffGateTest {
  @Test
  fun admitsOneHandoffAndIgnoresLateRelease() {
    val gate = DeviceHandoffGate()
    val first = requireNotNull(gate.tryAcquire())
    assertTrue(gate.isActive())
    assertNull(gate.tryAcquire())

    first.close()
    assertFalse(gate.isActive())
    val replacement = requireNotNull(gate.tryAcquire())
    first.close()
    assertTrue(gate.isActive())
    replacement.close()
    assertFalse(gate.isActive())
  }
}
