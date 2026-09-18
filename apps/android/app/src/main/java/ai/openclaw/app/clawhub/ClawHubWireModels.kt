package ai.openclaw.app.clawhub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val ClawHubJson =
  Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
  }

@Serializable
internal data class ClawHubPackagePageWire(
  val items: List<ClawHubPackageWire> = emptyList(),
  val nextCursor: String? = null,
)

@Serializable
internal data class ClawHubSearchPageWire(
  val results: List<ClawHubSearchResultWire> = emptyList(),
)

@Serializable
internal data class ClawHubSearchResultWire(
  val score: Double? = null,
  @SerialName("package") val packageValue: ClawHubPackageWire? = null,
)

@Serializable
internal data class ClawHubPackageWire(
  val artifact: ClawHubArtifactWire? = null,
  val categories: List<String>? = null,
  val channel: String? = null,
  val compatibility: ClawHubCompatibilityWire? = null,
  val createdAt: Long? = null,
  val displayName: String? = null,
  val family: String? = null,
  val icon: String? = null,
  val isOfficial: Boolean? = null,
  val latestVersion: String? = null,
  val name: String? = null,
  val ownerHandle: String? = null,
  val runtimeId: String? = null,
  val scanStatus: String? = null,
  val stats: ClawHubStatsWire? = null,
  val summary: String? = null,
  val topics: List<String>? = null,
  val updatedAt: Long? = null,
  val verification: ClawHubVerificationWire? = null,
  val verificationTier: String? = null,
)

@Serializable
internal data class ClawHubStatsWire(
  val downloads: Long? = null,
  val installs: Long? = null,
  val stars: Long? = null,
  val versions: Long? = null,
)

@Serializable
internal data class ClawHubArtifactWire(
  val format: String? = null,
  val kind: String? = null,
  val npmIntegrity: String? = null,
  val npmShasum: String? = null,
  val sha256: String? = null,
  val size: Long? = null,
)

@Serializable
internal data class ClawHubCompatibilityWire(
  val builtWithOpenClawVersion: String? = null,
  val minGatewayVersion: String? = null,
  val pluginApiRange: String? = null,
)

@Serializable
internal data class ClawHubVerificationWire(
  val hasProvenance: Boolean? = null,
  val scanStatus: String? = null,
  val sourceCommit: String? = null,
  val sourcePath: String? = null,
  val sourceRepo: String? = null,
  val sourceTag: String? = null,
  val summary: String? = null,
  val tier: String? = null,
  val trustedOpenClawPlugin: Boolean? = null,
)

@Serializable
internal data class ClawHubPackageDetailWire(
  @SerialName("package") val packageValue: ClawHubPackageWire? = null,
  val owner: ClawHubOwnerWire? = null,
)

@Serializable
internal data class ClawHubOwnerWire(
  val handle: String? = null,
  val displayName: String? = null,
  val image: String? = null,
)

@Serializable
internal data class ClawHubReadinessWire(
  @SerialName("package") val packageValue: ClawHubPackageWire? = null,
  val ready: Boolean? = null,
  val checks: List<ClawHubReadinessCheckWire>? = null,
  val blockers: List<String>? = null,
)

@Serializable
internal data class ClawHubReadinessCheckWire(
  val id: String? = null,
  val label: String? = null,
  val status: String? = null,
  val message: String? = null,
)

@Serializable
internal data class ClawHubVersionsWire(
  val items: List<ClawHubVersionWire> = emptyList(),
  val nextCursor: String? = null,
)

@Serializable
internal data class ClawHubVersionWire(
  val version: String? = null,
  val createdAt: Long? = null,
  val changelog: String? = null,
  val distTags: List<String>? = null,
  val license: String? = null,
)

@Serializable
internal data class ClawHubSkillDetailWire(
  val skill: ClawHubSkillWire? = null,
  val latestVersion: ClawHubVersionWire? = null,
  val metadata: ClawHubSkillMetadataWire? = null,
  val owner: ClawHubOwnerWire? = null,
)

@Serializable
internal data class ClawHubSkillWire(
  val slug: String? = null,
  val displayName: String? = null,
  val summary: String? = null,
  val icon: String? = null,
  val description: String? = null,
  val createdAt: Long? = null,
  val updatedAt: Long? = null,
)

@Serializable
internal data class ClawHubSkillMetadataWire(
  val os: List<String>? = null,
  val systems: List<String>? = null,
)

@Serializable
internal data class ClawHubSecurityWire(
  val overview: String? = null,
  val securityAuditUrl: String? = null,
  @SerialName("package") val packageValue: ClawHubPackageWire? = null,
  val release: ClawHubSecurityReleaseWire? = null,
  val trust: ClawHubSecurityTrustWire? = null,
)

@Serializable
internal data class ClawHubSecurityReleaseWire(
  val releaseId: String? = null,
  val version: String? = null,
  val artifactKind: String? = null,
  val artifactSha256: String? = null,
  val npmIntegrity: String? = null,
  val npmShasum: String? = null,
  val createdAt: Long? = null,
)

@Serializable
internal data class ClawHubSecurityTrustWire(
  val scanStatus: String? = null,
  val moderationState: String? = null,
  val blockedFromDownload: Boolean? = null,
  val reasons: List<String>? = null,
  val pending: Boolean? = null,
  val stale: Boolean? = null,
)
