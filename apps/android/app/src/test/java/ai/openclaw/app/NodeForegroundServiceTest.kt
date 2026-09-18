package ai.openclaw.app

import android.app.Notification
import android.app.Service
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NodeForegroundServiceTest {
  @After
  fun resetNodeServiceStartSuppression() {
    NodeForegroundService.resume(RuntimeEnvironment.getApplication(), startNow = false)
  }

  @Test
  fun stableNotificationStateReemitsWhenLocaleChanges() =
    runBlocking {
      val localeChanges = MutableStateFlow(0L)
      val firstEmission = CompletableDeferred<Unit>()
      val emissions = mutableListOf<LocaleAwareNotificationState<String>>()
      val collection =
        launch(start = CoroutineStart.UNDISPATCHED) {
          refreshNotificationOnLocaleChanges(
            states = flowOf("stable"),
            localeChanges = localeChanges,
          ).take(2)
            .collect { update ->
              emissions += update
              if (emissions.size == 1) firstEmission.complete(Unit)
            }
        }

      firstEmission.await()
      localeChanges.value = 1L
      collection.join()

      assertEquals(
        listOf(
          LocaleAwareNotificationState(state = "stable", localeRevision = 0L),
          LocaleAwareNotificationState(state = "stable", localeRevision = 1L),
        ),
        emissions,
      )
    }

  @Test
  fun restoreStickyRuntimeCreatesAndActivatesMissingProcessRuntime() =
    runBlocking {
      val restoredRuntime = Any()
      var created = false
      var activated: Any? = null

      restoreStickyRuntime(
        createRuntime = {
          created = true
          restoredRuntime
        },
        disconnectRequested = { false },
        disconnectRuntime = {},
        activateRuntime = {
          activated = it
          true
        },
      )

      assertTrue(created)
      assertSame(restoredRuntime, activated)
    }

  @Test
  fun restoreStickyRuntimeDoesNotCreateAfterDisconnectAlreadyWon() =
    runBlocking {
      var created = false

      restoreStickyRuntime(
        createRuntime = {
          created = true
          Any()
        },
        disconnectRequested = { true },
        disconnectRuntime = {},
        activateRuntime = { true },
      )

      assertFalse(created)
    }

  @Test
  fun restoreStickyRuntimeHonorsDisconnectRequestedDuringCreation() =
    runBlocking {
      val restoredRuntime = Any()
      var disconnectRequested = false
      var disconnected: Any? = null
      var activated = false

      restoreStickyRuntime(
        createRuntime = {
          disconnectRequested = true
          restoredRuntime
        },
        disconnectRequested = { disconnectRequested },
        disconnectRuntime = { disconnected = it },
        activateRuntime = {
          activated = true
          true
        },
      )

      assertSame(restoredRuntime, disconnected)
      assertFalse(activated)
    }

  @Test
  fun restoreStickyRuntimeDisconnectsWhenActivationDeclinesOwnership() =
    runBlocking {
      val restoredRuntime = Any()
      var disconnected: Any? = null

      restoreStickyRuntime(
        createRuntime = { restoredRuntime },
        disconnectRequested = { false },
        disconnectRuntime = { disconnected = it },
        activateRuntime = { false },
      )

      assertSame(restoredRuntime, disconnected)
    }

  @Test
  fun coldStopDoesNotCreateRuntime() {
    val app = RuntimeEnvironment.getApplication() as NodeApp
    assertNull(app.peekRuntime())
    val controller = Robolectric.buildService(NodeForegroundService::class.java).create()

    try {
      val result =
        controller
          .get()
          .onStartCommand(
            Intent(app, NodeForegroundService::class.java)
              .setAction("ai.openclaw.app.action.STOP"),
            0,
            1,
          )

      assertEquals(Service.START_NOT_STICKY, result)
      assertNull(app.peekRuntime())

      val secondResult = controller.get().onStartCommand(Intent(app, NodeForegroundService::class.java), 0, 2)
      assertEquals(Service.START_NOT_STICKY, secondResult)
      assertEquals(2, Shadows.shadowOf(controller.get()).stopSelfResultId)
      assertNull(app.peekRuntime())
    } finally {
      closeNodeServiceTestFixture(controller, app)
    }
  }

  @Test
  fun explicitResumeAfterStopRestoresStickyServiceOwnership() {
    val app = RuntimeEnvironment.getApplication() as NodeApp
    val controller = Robolectric.buildService(NodeForegroundService::class.java).create()

    try {
      val stopped =
        controller
          .get()
          .onStartCommand(
            Intent(app, NodeForegroundService::class.java)
              .setAction("ai.openclaw.app.action.STOP"),
            0,
            1,
          )
      val resumed =
        controller
          .get()
          .onStartCommand(
            Intent(app, NodeForegroundService::class.java)
              .setAction("ai.openclaw.app.action.RESUME"),
            0,
            2,
          )

      assertEquals(Service.START_NOT_STICKY, stopped)
      assertEquals(Service.START_STICKY, resumed)
    } finally {
      closeNodeServiceTestFixture(controller, app)
    }
  }

  @Test
  fun buildNotificationSetsLaunchIntent() {
    val service = Robolectric.buildService(NodeForegroundService::class.java).get()
    val notification = buildNotification(service)

    val pendingIntent = notification.contentIntent
    assertNotNull(pendingIntent)

    val savedIntent = Shadows.shadowOf(pendingIntent).savedIntent
    assertNotNull(savedIntent)
    assertEquals(MainActivity::class.java.name, savedIntent.component?.className)

    val expectedFlags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    assertEquals(expectedFlags, savedIntent.flags and expectedFlags)
  }

  private fun buildNotification(service: NodeForegroundService): Notification {
    val method =
      NodeForegroundService::class.java.getDeclaredMethod(
        "buildNotification",
        String::class.java,
        String::class.java,
      )
    method.isAccessible = true
    return method.invoke(service, "Title", "Text") as Notification
  }
}
