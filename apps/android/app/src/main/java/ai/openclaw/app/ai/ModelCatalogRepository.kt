package ai.openclaw.app.ai

import ai.openclaw.app.gateway.GatewayMethod
import ai.openclaw.app.i18n.nativeString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/** Generation-bound projection of Gateway Provider/model facts. */
internal class ModelCatalogRepository(
  private val scope: CoroutineScope,
  private val transport: AiGatewayTransport,
  private val json: Json,
  private val actionsEnabled: Boolean = true,
) {
  private val mutableState = MutableStateFlow(ModelCatalogState())
  val state = mutableState.asStateFlow()
  private val refreshSequence = AtomicLong()
  private val stateLock = Any()
  private var activeConnection: AiGatewayConnection? = null
  private var selectedSessionKey: String? = null

  fun selectSession(sessionKey: String?) {
    val normalized = sessionKey?.trim()?.takeIf(String::isNotEmpty)
    val changed =
      synchronized(stateLock) {
        if (selectedSessionKey == normalized) {
          false
        } else {
          selectedSessionKey = normalized
          true
        }
      }
    if (!changed) return
    refreshSequence.incrementAndGet()
    if (captureActive() != null) {
      mutableState.value = ModelCatalogState(status = ModelCatalogStatus.Loading, refreshing = true)
      refresh(force = false)
    }
  }

  fun onConnectionChanged() {
    val connection = transport.capture()
    val changed =
      synchronized(stateLock) {
        if (activeConnection == connection) {
          false
        } else {
          activeConnection = connection
          true
        }
      }
    if (!changed) return
    refreshSequence.incrementAndGet()
    when {
      connection == null -> mutableState.value = ModelCatalogState()
      connection.missingRequiredAiMethods().isNotEmpty() ->
        mutableState.value =
          ModelCatalogState(
            status = ModelCatalogStatus.Incompatible(connection.missingRequiredAiMethods()),
          )
      else -> {
        mutableState.value = ModelCatalogState(status = ModelCatalogStatus.Loading, refreshing = true)
        refresh(force = false)
      }
    }
  }

  fun refresh(force: Boolean = true) {
    if (!actionsEnabled) return
    val sequence = refreshSequence.incrementAndGet()
    scope.launch(start = CoroutineStart.UNDISPATCHED) { load(sequence, force) }
  }

  fun clear() {
    synchronized(stateLock) { activeConnection = null }
    refreshSequence.incrementAndGet()
    mutableState.value = ModelCatalogState()
  }

  internal fun applyFixture(snapshot: ModelCatalogSnapshot) {
    synchronized(stateLock) { activeConnection = null }
    refreshSequence.incrementAndGet()
    mutableState.value = ModelCatalogState(ModelCatalogStatus.Ready(snapshot))
  }

  private fun captureActive(): AiGatewayConnection? {
    val captured = transport.capture() ?: return null
    return synchronized(stateLock) { captured.takeIf { it == activeConnection } }
  }

  private suspend fun load(
    sequence: Long,
    force: Boolean,
  ) {
    val connection = captureActive() ?: return
    val sessionKey = synchronized(stateLock) { selectedSessionKey }
    if (connection.missingRequiredAiMethods().isNotEmpty()) return
    publish(connection, sequence) { current -> current.copy(refreshing = true) }
    try {
      val response =
        transport.request(
          connection,
          GatewayMethod.ModelsList.rawValue,
          modelsListParams(connection.defaultAgentId, sessionKey = sessionKey, refresh = force),
        )
      val snapshot = parseModelCatalog(response, json) ?: error("Malformed model catalog")
      publish(connection, sequence) {
        ModelCatalogState(
          status = ModelCatalogStatus.Ready(snapshot),
          refreshing = false,
        )
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Throwable) {
      publish(connection, sequence) {
        ModelCatalogState(
          status = ModelCatalogStatus.Failed(nativeString("Could not load AI models.")),
          refreshing = false,
        )
      }
    }
  }

  private fun publish(
    connection: AiGatewayConnection,
    sequence: Long,
    update: (ModelCatalogState) -> ModelCatalogState,
  ): Boolean {
    if (refreshSequence.get() != sequence) return false
    return transport.publish(connection) {
      if (refreshSequence.get() == sequence) mutableState.value = update(mutableState.value)
    }
  }
}
