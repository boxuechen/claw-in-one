package ai.openclaw.app.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservableServiceInstanceTest {
  @Test
  fun disconnectWithdrawsTheCurrentInstance() {
    val state = ObservableServiceInstance<Any>()
    val service = Any()

    state.connect(service)
    state.disconnect(service)

    assertFalse(state.isConnected.value)
    assertNull(state.connection.value.instance)
  }

  @Test
  fun staleDisconnectDoesNotWithdrawAReplacement() {
    val state = ObservableServiceInstance<Any>()
    val first = Any()
    val replacement = Any()

    state.connect(first)
    state.connect(replacement)
    state.disconnect(first)

    assertTrue(state.isConnected.value)
    assertSame(replacement, state.connection.value.instance)
  }
}
