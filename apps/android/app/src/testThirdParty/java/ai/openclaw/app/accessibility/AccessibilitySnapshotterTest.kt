package ai.openclaw.app.accessibility

import ai.openclaw.app.node.MobileUiTarget
import android.graphics.Rect
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilitySnapshotterTest {
  @Test
  fun observableServiceInstanceTracksTheLiveInstance() {
    val state = ObservableServiceInstance<Any>()
    val first = Any()
    val second = Any()

    assertEquals(AccessibilityServiceConnection<Any>(instance = null, generation = 0), state.connection.value)
    assertFalse(state.isConnected.value)

    state.connect(first)
    assertTrue(state.connection.value.instance === first)
    assertEquals(1L, state.connection.value.generation)
    assertTrue(state.isConnected.value)

    state.connect(second)
    state.disconnect(first)
    assertTrue(state.connection.value.instance === second)
    assertEquals(2L, state.connection.value.generation)
    assertTrue(state.isConnected.value)

    state.disconnect(second)
    assertEquals(null, state.connection.value.instance)
    assertEquals(2L, state.connection.value.generation)
    assertFalse(state.isConnected.value)
  }

  @Test
  fun normalizerCapsSnapshotsAtMaximumNodeCountAndRecyclesDiscardedNodes() {
    val children = List(MAX_NODES + 5) { index -> FakeNode(text = "node-$index") }
    val root = FakeNode(boundsInScreen = Rect(), children = children)

    val result = AccessibilityTreeNormalizer.normalize(root)

    assertEquals(MAX_NODES, result.nodes.size)
    assertEquals("n0", result.nodes.first().ref)
    assertEquals("n${MAX_NODES - 1}", result.nodes.last().ref)
    assertTrue(root.recycled)
    assertTrue(children.takeLast(5).all(FakeNode::recycled))
    assertFalse(children.take(MAX_NODES).any(FakeNode::recycled))

    result.retainedNodes.values.forEach(AccessibilityNodeAdapter::recycle)
    assertTrue(children.all(FakeNode::recycled))
  }

  @Test
  fun normalizerDoesNotWalkBelowMaximumDepth() {
    var root = FakeNode(text = "deepest")
    repeat(MAX_DEPTH + 2) { depth ->
      root = FakeNode(text = "depth-$depth", children = listOf(root))
    }

    val result = AccessibilityTreeNormalizer.normalize(root)

    assertEquals(MAX_DEPTH + 1, result.nodes.size)
    result.retainedNodes.values.forEach(AccessibilityNodeAdapter::recycle)
  }

  @Test
  fun passwordTextIsRedactedAndLongTextIsBounded() {
    assertTrue(shouldRedactText(isPassword = true, isEditable = false, inputType = 0))
    assertTrue(
      shouldRedactText(
        isPassword = false,
        isEditable = true,
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
      ),
    )
    assertFalse(
      shouldRedactText(
        isPassword = false,
        isEditable = true,
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
      ),
    )
    assertEquals("[redacted]", normalizeNodeText("hunter2", sensitive = true))

    val truncated = normalizeNodeText("x".repeat(MAX_TEXT_PER_NODE + 10), sensitive = false)
    assertEquals(MAX_TEXT_PER_NODE, truncated?.length)
    assertTrue(truncated?.endsWith("…") == true)
  }

  @Test
  fun actionNamesUseStableVocabularyAndOrdering() {
    val actions =
      stableActionNames(
        listOf(
          AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
          AccessibilityNodeInfo.ACTION_SET_TEXT,
          AccessibilityNodeInfo.ACTION_CLICK,
          AccessibilityNodeInfo.ACTION_COPY,
          AccessibilityNodeInfo.ACTION_FOCUS,
        ),
      )

    assertEquals(listOf("activate", "set_text", "scroll_backward", "focus"), actions)
  }

  @Test
  fun wrongSnapshotOrMissingRefIsStaleAndReplacementReleasesOldNodes() {
    val released = mutableListOf<String>()
    val store = SnapshotGenerationStore<String>(released::add)
    store.replace(
      snapshotId = "snapshot-1",
      packageName = "example.one",
      uiEpoch = 11,
      connectionGeneration = 21,
      target = MAIN_TARGET,
      values = mapOf("n0" to "first"),
    )

    assertEquals(GenerationTarget.Stale, store.resolve("wrong-snapshot", "n0"))
    assertEquals(GenerationTarget.Stale, store.resolve("snapshot-1", "missing"))
    assertEquals(GenerationTarget.Found("first"), store.resolve("snapshot-1", "n0"))

    store.replace(
      snapshotId = "snapshot-2",
      packageName = "example.two",
      uiEpoch = 12,
      connectionGeneration = 22,
      target = MobileUiTarget.MainDisplay("example.two"),
      values = emptyMap(),
    )
    assertEquals(listOf("first"), released)
    assertEquals("example.two", store.packageName)
    assertEquals(12L, store.uiEpoch)
    assertEquals(22L, store.connectionGeneration)
    assertEquals(MobileUiTarget.MainDisplay("example.two"), store.target)
  }

  @Test
  fun targetWindowSelectionPrefersFocusedThenActiveAndRejectsEqualPriority() {
    val background = AccessibilityWindowCandidate("background", focused = false, active = false, layer = 10)
    val active = AccessibilityWindowCandidate("active", focused = false, active = true, layer = 5)
    val focused = AccessibilityWindowCandidate("focused", focused = true, active = false, layer = 1)

    assertEquals("focused", selectAccessibilityWindowCandidate(listOf(background, active, focused)))
    assertEquals(
      null,
      selectAccessibilityWindowCandidate(
        listOf(
          AccessibilityWindowCandidate("first", focused = true, active = true, layer = 4),
          AccessibilityWindowCandidate("second", focused = true, active = true, layer = 4),
        ),
      ),
    )
  }

  @Test
  fun uiEpochsAreScopedToTheExactDisplayAndPackage() {
    val epochs = AccessibilityUiEpochs()
    val otherPackage = MobileUiTarget.MainDisplay("example.other")
    val otherDisplay = PREVIEW_TARGET.copy(displayId = PREVIEW_TARGET.displayId + 1)

    epochs.advance(MAIN_TARGET)
    epochs.advance(otherPackage)
    epochs.advance(otherDisplay)

    assertEquals(1L, epochs.current(MAIN_TARGET))
    assertEquals(1L, epochs.current(otherPackage))
    assertEquals(0L, epochs.current(PREVIEW_TARGET))
    assertEquals(1L, epochs.current(otherDisplay))
  }

  @Test
  fun executorRejectsAnActionFromTheWrongSnapshotGeneration() =
    runTest {
      val service = Robolectric.buildService(OpenClawAccessibilityService::class.java).create().get()
      val executor = AccessibilityActionExecutor(connectionProvider = { testConnection(service) })
      val observed = executor.observe(MAIN_TARGET)

      val result = executor.act(MAIN_TARGET, "${observed.id}-stale", MobileUiAction.Wait(0))

      assertEquals(ActionOutcomeCode.TargetStale, result.code)
      executor.close()
      service.onDestroy()
    }

  @Test
  fun sensitiveNodeRedactsTextAndContentDescription() {
    val sensitiveNode =
      FakeNode(
        text = "secret text",
        contentDescription = "secret description",
        viewId = "example:id/password",
        password = true,
      )

    val result = AccessibilityTreeNormalizer.normalize(sensitiveNode)

    assertEquals("[redacted]", result.nodes.single().text)
    assertEquals("[redacted]", result.nodes.single().contentDescription)
    assertEquals("example:id/password", result.nodes.single().viewId)
    result.retainedNodes.values.forEach(AccessibilityNodeAdapter::recycle)
  }

  @Test
  fun normalizerDoesNotFetchBeyondTraversalBudget() {
    val children = List(MAX_VISITED_NODES * 2) { index -> FakeNode(text = "wide-$index") }
    val root = FakeNode(boundsInScreen = Rect(), children = children)

    val result = AccessibilityTreeNormalizer.normalize(root)

    assertEquals(MAX_NODES, result.nodes.size)
    assertEquals(MAX_VISITED_NODES - 1, root.childRequests)
    result.retainedNodes.values.forEach(AccessibilityNodeAdapter::recycle)
    assertTrue(root.recycled)
    assertTrue(children.take(MAX_VISITED_NODES - 1).all(FakeNode::recycled))
    assertFalse(children.drop(MAX_VISITED_NODES - 1).any(FakeNode::recycled))
  }

  @Test
  fun coordinateGestureFailsClosedWhenPackageIdentityIsMissingOrChanged() =
    runTest {
      val service = Robolectric.buildService(OpenClawAccessibilityService::class.java).create().get()
      val cases =
        listOf(
          null to "example.current",
          "example.expected" to null,
          "example.expected" to "example.current",
        )

      cases.forEachIndexed { index, (expectedPackage, currentPackage) ->
        val executor =
          AccessibilityActionExecutor(
            connectionProvider = { testConnection(service) },
            captureSnapshot = { _, _ -> fakeCapture("snapshot-$index", expectedPackage) },
            targetPackageProvider = { _, _ -> currentPackage },
            uiEpochProvider = { 20 },
          )
        val observed = executor.observe(MAIN_TARGET)

        val result = executor.act(MAIN_TARGET, observed.id, MobileUiAction.Tap(10, 20))

        assertEquals(ActionOutcomeCode.PackageChanged, result.code)
        executor.close()
      }
      service.onDestroy()
    }

  @Test
  fun coordinateGestureIsStaleAfterUiEpochAdvances() =
    runTest {
      val service = Robolectric.buildService(OpenClawAccessibilityService::class.java).create().get()
      val executor =
        AccessibilityActionExecutor(
          connectionProvider = { testConnection(service) },
          captureSnapshot = { _, _ -> fakeCapture("epoch-snapshot", "example.package") },
          targetPackageProvider = { _, _ -> "example.package" },
        )
      val observed = executor.observe(MAIN_TARGET)
      val capturedEpoch = OpenClawAccessibilityService.uiEpoch(MAIN_TARGET)

      sendAccessibilityEvent(service, AccessibilityEvent.TYPE_VIEW_FOCUSED)
      assertEquals(capturedEpoch, OpenClawAccessibilityService.uiEpoch(MAIN_TARGET))
      repeat(6) { OpenClawAccessibilityService.advanceUiEpoch(MAIN_TARGET) }
      assertEquals(capturedEpoch + 6, OpenClawAccessibilityService.uiEpoch(MAIN_TARGET))
      val result = executor.act(MAIN_TARGET, observed.id, MobileUiAction.Tap(10, 20))

      assertEquals(ActionOutcomeCode.TargetStale, result.code)
      assertEquals("UI changed since observe; re-observe before coordinate actions", result.message)
      executor.close()
      service.onDestroy()
    }

  @Test
  fun actionIsStaleAfterAccessibilityServiceReconnects() =
    runTest {
      val service = Robolectric.buildService(OpenClawAccessibilityService::class.java).create().get()
      var connectionGeneration = 70L
      val executor =
        AccessibilityActionExecutor(
          connectionProvider = { testConnection(service, connectionGeneration) },
          captureSnapshot = { _, _ -> fakeCapture("connection-snapshot", "example.package") },
          targetPackageProvider = { _, _ -> "example.package" },
          uiEpochProvider = { 80 },
        )
      val observed = executor.observe(MAIN_TARGET)
      connectionGeneration += 1

      val result = executor.act(MAIN_TARGET, observed.id, MobileUiAction.Tap(10, 20))

      assertEquals(ActionOutcomeCode.TargetStale, result.code)
      assertEquals("Accessibility service reconnected; re-observe before acting", result.message)
      executor.close()
      service.onDestroy()
    }

  @Test
  fun actionCannotCrossPreviewBindingRevisions() =
    runTest {
      val service = Robolectric.buildService(OpenClawAccessibilityService::class.java).create().get()
      val executor =
        AccessibilityActionExecutor(
          connectionProvider = { testConnection(service) },
          captureSnapshot = { _, target -> fakeCapture("preview-snapshot", target.packageName, target = target) },
          targetPackageProvider = { _, target -> target.packageName },
          uiEpochProvider = { 80 },
        )
      val observed = executor.observe(PREVIEW_TARGET)

      val result =
        executor.act(
          PREVIEW_TARGET.copy(bindingRevision = PREVIEW_TARGET.bindingRevision + 1),
          observed.id,
          MobileUiAction.Wait(0),
        )

      assertEquals(ActionOutcomeCode.TargetStale, result.code)
      assertEquals("Android target changed; observe again before acting", result.message)
      executor.close()
      service.onDestroy()
    }

  @Test
  fun previewGlobalActionFailsWithoutTouchingTheMainDisplay() =
    runTest {
      val service = Robolectric.buildService(OpenClawAccessibilityService::class.java).create().get()
      val executor = AccessibilityActionExecutor(connectionProvider = { testConnection(service) })

      val result = executor.act(PREVIEW_TARGET, "unused", MobileUiAction.GlobalAction(GlobalActionName.Back))

      assertEquals(ActionOutcomeCode.ActionNotSupported, result.code)
      executor.close()
      service.onDestroy()
    }

  @Test
  @Suppress("DEPRECATION")
  fun observeCannotPublishAGenerationAfterExecutorCloses() =
    runTest {
      val service = Robolectric.buildService(OpenClawAccessibilityService::class.java).create().get()
      val node = AccessibilityNodeInfo.obtain()
      lateinit var executor: AccessibilityActionExecutor
      executor =
        AccessibilityActionExecutor(
          connectionProvider = { testConnection(service, generation = 100) },
          captureSnapshot = { _, _ ->
            executor.close()
            fakeCapture(
              id = "closed-snapshot",
              packageName = "example.package",
              nodesByRef = mapOf("n0" to node),
            )
          },
          targetPackageProvider = { _, _ -> "example.package" },
          uiEpochProvider = { 90 },
        )

      val error = assertThrows(AccessibilityServiceDisabledException::class.java) { executor.observe(MAIN_TARGET) }
      val result = executor.act(MAIN_TARGET, "closed-snapshot", MobileUiAction.GlobalAction(GlobalActionName.Home))

      assertEquals("Accessibility executor closed during observe", error.message)
      assertEquals(ActionOutcomeCode.ServiceDisabled, result.code)
      service.onDestroy()
    }

  @Test
  fun nodeActionFailsClosedWhenPackageIdentityIsMissingOrChanged() =
    runTest {
      val service = Robolectric.buildService(OpenClawAccessibilityService::class.java).create().get()
      val cases =
        listOf(
          null to "example.current",
          "example.expected" to null,
          "example.expected" to "example.current",
        )

      cases.forEachIndexed { index, (expectedPackage, currentPackage) ->
        val executor =
          AccessibilityActionExecutor(
            connectionProvider = { testConnection(service) },
            captureSnapshot = { _, _ -> fakeCapture("node-snapshot-$index", expectedPackage) },
            targetPackageProvider = { _, _ -> currentPackage },
            uiEpochProvider = { 40 },
          )
        val observed = executor.observe(MAIN_TARGET)

        val result = executor.act(MAIN_TARGET, observed.id, MobileUiAction.Activate("n0"))

        assertEquals(ActionOutcomeCode.PackageChanged, result.code)
        executor.close()
      }
      service.onDestroy()
    }

  @Test
  @Suppress("DEPRECATION")
  fun nodeActionUsesNodeFreshnessInsteadOfUiEpoch() =
    runTest {
      val service = Robolectric.buildService(OpenClawAccessibilityService::class.java).create().get()
      val node = AccessibilityNodeInfo.obtain()
      var uiEpoch = 50L
      val executor =
        AccessibilityActionExecutor(
          connectionProvider = { testConnection(service) },
          captureSnapshot = { _, _ ->
            fakeCapture(
              id = "node-epoch-snapshot",
              packageName = "example.package",
              nodesByRef = mapOf("n0" to node),
            )
          },
          targetPackageProvider = { _, _ -> "example.package" },
          uiEpochProvider = { uiEpoch },
        )
      val observed = executor.observe(MAIN_TARGET)
      uiEpoch += 1

      val result = executor.act(MAIN_TARGET, observed.id, MobileUiAction.Activate("n0"))

      assertTrue(
        result.code == ActionOutcomeCode.TargetNotFound ||
          result.code == ActionOutcomeCode.ActionNotSupported,
      )
      assertFalse(result.message?.contains("UI changed since observe") == true)
      executor.close()
      service.onDestroy()
    }

  @Test
  @Suppress("DEPRECATION")
  fun setTextChecksSecureContentAfterTheFinalNodeRefresh() =
    runTest {
      val service = Robolectric.buildService(OpenClawAccessibilityService::class.java).create().get()
      val node =
        AccessibilityNodeInfo.obtain().apply {
          isEditable = true
          isPassword = true
          addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        }
      val shadowNode = shadowOf(node).apply { setRefreshReturnValue(true) }
      val executor =
        AccessibilityActionExecutor(
          connectionProvider = { testConnection(service) },
          captureSnapshot = { _, _ ->
            fakeCapture(
              id = "secure-text-snapshot",
              packageName = "example.package",
              nodesByRef = mapOf("n0" to node),
            )
          },
          targetPackageProvider = { _, _ -> "example.package" },
          uiEpochProvider = { 60 },
        )
      val observed = executor.observe(MAIN_TARGET)

      val result = executor.act(MAIN_TARGET, observed.id, MobileUiAction.SetText("n0", "must not be sent"))

      assertEquals(ActionOutcomeCode.SecureContent, result.code)
      assertTrue(shadowNode.performedActions.isEmpty())
      executor.close()
      service.onDestroy()
    }

  @Test
  fun devUiEnablesNodeActionsOnlyForMatchingKnownPackages() {
    assertFalse(canRunNodeActions(snapshotPackageName = null, foregroundPackageName = "example"))
    assertFalse(canRunNodeActions(snapshotPackageName = "example", foregroundPackageName = null))
    assertFalse(canRunNodeActions(snapshotPackageName = "example", foregroundPackageName = "other"))
    assertTrue(canRunNodeActions(snapshotPackageName = "example", foregroundPackageName = "example"))
  }

  @Test
  fun globalActionDoesNotRequirePackageIdentity() =
    runTest {
      val service = Robolectric.buildService(OpenClawAccessibilityService::class.java).create().get()
      val executor =
        AccessibilityActionExecutor(
          connectionProvider = { testConnection(service) },
          targetPackageProvider = { _, _ -> error("Global actions must not query the target package") },
          uiEpochProvider = { error("Global actions must not query the UI epoch") },
        )

      val result = executor.act(MAIN_TARGET, "no-snapshot", MobileUiAction.GlobalAction(GlobalActionName.Home))

      assertTrue(result.code == ActionOutcomeCode.Completed || result.code == ActionOutcomeCode.ActionRejected)
      executor.close()
      service.onDestroy()
    }
}

private fun testConnection(
  service: OpenClawAccessibilityService,
  generation: Long = 0,
): AccessibilityServiceConnection<OpenClawAccessibilityService> = AccessibilityServiceConnection(instance = service, generation = generation)

private fun fakeCapture(
  id: String,
  packageName: String?,
  nodesByRef: Map<String, AccessibilityNodeInfo> = emptyMap(),
  target: MobileUiTarget = MAIN_TARGET,
): AccessibilitySnapshotCapture =
  AccessibilitySnapshotCapture(
    snapshot =
      MobileUiSnapshot(
        id = id,
        capturedAtMs = 0,
        packageName = packageName,
        windowTitle = null,
        target = target,
        nodes = emptyList(),
      ),
    nodesByRef = nodesByRef,
  )

private val MAIN_TARGET = MobileUiTarget.MainDisplay("example.package")
private val PREVIEW_TARGET =
  MobileUiTarget.VScreenDisplay(
    packageName = "example.package",
    displayId = 7,
    attachmentId = "82345678-1234-4123-8123-123456789abc",
    targetGeneration = 1,
    bindingRevision = 3,
  )

@Suppress("DEPRECATION")
private fun sendAccessibilityEvent(
  service: OpenClawAccessibilityService,
  eventType: Int,
) {
  val event = AccessibilityEvent.obtain(eventType)
  try {
    event.packageName = MAIN_TARGET.packageName
    service.onAccessibilityEvent(event)
  } finally {
    event.recycle()
  }
}

private class FakeNode(
  override val className: String? = "android.view.View",
  override val text: String? = null,
  override val contentDescription: String? = null,
  override val viewId: String? = null,
  override val boundsInScreen: Rect = Rect(0, 0, 10, 10),
  override val clickable: Boolean = false,
  override val editable: Boolean = false,
  override val scrollable: Boolean = false,
  override val enabled: Boolean = true,
  override val focused: Boolean = false,
  override val password: Boolean = false,
  override val inputType: Int = 0,
  override val actionIds: List<Int> = emptyList(),
  private val children: List<FakeNode> = emptyList(),
) : AccessibilityNodeAdapter {
  var childRequests: Int = 0
    private set
  var recycled: Boolean = false
    private set

  override val childCount: Int
    get() = children.size

  override fun childAt(index: Int): AccessibilityNodeAdapter {
    childRequests += 1
    return children[index]
  }

  override fun recycle() {
    check(!recycled) { "Fake node recycled twice" }
    recycled = true
  }
}
