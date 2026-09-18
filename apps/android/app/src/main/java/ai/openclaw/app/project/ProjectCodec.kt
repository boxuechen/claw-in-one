package ai.openclaw.app.project

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal const val PROJECT_CREATE_METHOD = "claw.projects.create"
internal const val PROJECT_READ_METHOD = "claw.projects.read"
internal const val PROJECT_CATALOG_METHOD = "claw.projects.catalog"
internal const val PROJECT_CAPABILITIES_METHOD = "claw.projects.capabilities"
internal const val PROJECT_RUN_LEASE_STATUS_METHOD = "claw.projects.run-lease.status"

internal class ProjectCodec(
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  fun createParams(intent: ProjectCreateIntent): String =
    buildJsonObject {
      put("intentId", JsonPrimitive(intent.intentId))
      put("nameRevision", JsonPrimitive(intent.nameRevision))
      put("naming", JsonPrimitive(intent.origin.wire))
      put("requestedName", JsonPrimitive(intent.requestedName))
    }.toString()

  fun readParams(intentId: String): String = buildJsonObject { put("intentId", JsonPrimitive(intentId)) }.toString()

  fun runLeaseStatusParams(projectId: String): String = buildJsonObject { put("projectId", JsonPrimitive(projectId)) }.toString()

  fun runLeaseStatus(payload: String): ProjectRunConflict? {
    val lease = root(payload).objectOrNull("lease") ?: return null
    return ProjectRunConflict(
      projectId = lease.requiredString("projectId"),
      ownerSessionKey = lease.requiredString("sessionKey"),
      ownerSessionId = lease.requiredString("sessionId"),
    )
  }

  fun creation(payload: String): ProjectCreationResult {
    val value = root(payload)
    val phase = value.requiredString("phase")
    return ProjectCreationResult(
      intentId = value.requiredString("intentId"),
      nameRevision = value.requiredString("nameRevision"),
      phase = phase,
      naming = ProjectNameOrigin.entries.first { it.wire == value.requiredString("naming") },
      displayName = value.requiredString("displayName"),
      repoRoot = value.requiredString("repoRoot"),
      project = value.objectOrNull("project")?.let(::project),
    )
  }

  fun readback(payload: String): ProjectCreationResult? {
    val value = root(payload)
    if (value.requiredString("phase") == "missing") return null
    return creation(payload)
  }

  fun catalog(payload: String): ProjectCatalogState.Ready {
    val value = root(payload)
    val projects =
      (value["projects"] as? kotlinx.serialization.json.JsonArray)
        ?.map { project(it as? JsonObject ?: error("Invalid Project row")) }
        ?: error("Missing Project catalog")
    return ProjectCatalogState.Ready(
      revision = (value["revision"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
      projects = projects,
      sessionBindings =
        (value["sessionBindings"] as? JsonObject)
          ?.mapValues { (_, binding) -> (binding as? JsonObject)?.requiredString("projectId") ?: error("Invalid Session binding") }
          .orEmpty(),
    )
  }

  fun capabilities(payload: String): DevelopmentCapabilitiesState.Ready {
    val value = root(payload)
    val capabilities =
      (value["readyCapabilities"] as? kotlinx.serialization.json.JsonArray)
        ?.map { element ->
          val wire =
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
              ?: error("Invalid capability")
          ai.openclaw.app.supervisor.DevelopmentCapability.entries.singleOrNull {
            it.wireName == wire
          } ?: error("Unknown capability")
        } ?: error("Missing capability snapshot")
    require(
      capabilities ==
        ai.openclaw.app.supervisor.DevelopmentCapability.entries
          .filter(capabilities::contains),
    )
    return DevelopmentCapabilitiesState.Ready(
      revision = value.requiredString("revision"),
      capabilities = capabilities,
    )
  }

  private fun project(value: JsonObject): ProjectRecord =
    ProjectRecord(
      id = value.requiredString("id"),
      displayName = value.requiredString("displayName"),
      repoRoot = value.requiredString("repoRoot"),
      source = value.requiredString("source"),
      agentId = value.string("agentId"),
    )

  private fun root(payload: String): JsonObject = json.parseToJsonElement(payload) as? JsonObject ?: error("Invalid Project response")
}

private val ProjectNameOrigin.wire: String
  get() = if (this == ProjectNameOrigin.Default) "default" else "custom"

private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key]?.takeUnless { it is JsonNull } as? JsonObject

private fun JsonObject.string(key: String): String? =
  (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }
    ?.content
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun JsonObject.requiredString(key: String): String = string(key) ?: error("Missing $key")
