package ai.openclaw.app.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDeliverySchedulerTest {
  private class Harness(
    scope: CoroutineScope,
    enabled: Boolean = true,
  ) {
    val events = mutableListOf<String>()
    var drain: suspend () -> Unit = {}
    val scheduler =
      ChatDeliveryScheduler(scope, enabled) {
        events += "drain"
        drain()
      }
  }

  @Test
  fun repeatedRequestsCoalesceIntoOneDrain() =
    runTest {
      val h = Harness(this)
      repeat(20) { h.scheduler.requestFlush() }
      runCurrent()
      assertEquals(listOf("drain"), h.events)
      h.scheduler.requestFlush()
      runCurrent()
      assertEquals(listOf("drain", "drain"), h.events)
    }

  @Test
  fun requestsDuringDrainScheduleOneFollowingPass() =
    runTest {
      val h = Harness(this)
      val release = CompletableDeferred<Unit>()
      h.drain = { release.await() }
      h.scheduler.requestFlush()
      runCurrent()
      repeat(20) { h.scheduler.requestDrain() }
      runCurrent()
      assertEquals(listOf("drain"), h.events)
      release.complete(Unit)
      runCurrent()
      assertEquals(listOf("drain", "drain"), h.events)
    }

  @Test
  fun disabledStoreDoesNothing() =
    runTest {
      val h = Harness(this, enabled = false)
      h.scheduler.requestFlush()
      h.scheduler.requestDrain()
      advanceUntilIdle()
      assertTrue(h.events.isEmpty())
    }

  @Test
  fun unexpectedFailureReleasesWorkerForALaterExplicitRequest() =
    runTest {
      val failures = mutableListOf<Throwable>()
      val owner =
        CoroutineScope(
          coroutineContext +
            SupervisorJob(coroutineContext[Job]) +
            CoroutineExceptionHandler { _, error -> failures += error },
        )
      try {
        val h = Harness(owner)
        h.drain = { error("uncertain operation") }
        h.scheduler.requestFlush()
        advanceUntilIdle()
        assertEquals(listOf("drain"), h.events)
        assertEquals(1, failures.size)
        h.drain = {}
        h.scheduler.requestFlush()
        runCurrent()
        assertEquals(listOf("drain", "drain"), h.events)
      } finally {
        owner.cancel()
      }
    }

  @Test
  fun immediateDispatcherCanRequestAnotherPassWithoutLosingWorkerOwnership() =
    runTest {
      val owner = CoroutineScope(coroutineContext + UnconfinedTestDispatcher(testScheduler))
      val h = Harness(owner)
      var passes = 0
      h.drain = { if (++passes == 1) h.scheduler.requestFlush() }
      h.scheduler.requestFlush()
      assertEquals(listOf("drain", "drain"), h.events)
      h.scheduler.requestDrain()
      assertEquals(3, passes)
    }
}
