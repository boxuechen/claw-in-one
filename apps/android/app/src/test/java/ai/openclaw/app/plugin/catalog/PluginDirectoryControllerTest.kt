package ai.openclaw.app.plugin.catalog

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginDirectoryControllerTest {
  @Test
  fun refreshAndSearchPublishIndependentImmutableDirectoryState() =
    runTest {
      val plugin = plugin("@openclaw/calendar")
      val controller =
        PluginDirectoryController(
          scope = this,
          repository =
            FakePluginCatalogRepository(
              recommended = OfficialPluginPage(listOf(plugin), nextCursor = "next"),
              search = listOf(OfficialPluginSearchMatch(plugin, score = 1.0)),
            ),
        )

      controller.refresh()
      runCurrent()
      controller.search(" calendar ")
      runCurrent()

      assertEquals(listOf(plugin), controller.state.value.items)
      assertEquals("next", controller.state.value.nextCursor)
      assertEquals("calendar", controller.state.value.query)
      assertEquals(
        listOf(plugin),
        controller.state.value.searchResults
          .map { it.plugin },
      )
      assertFalse(controller.state.value.refreshing)
      assertFalse(controller.state.value.searching)
    }

  private fun plugin(packageName: String): OfficialPluginSummary =
    OfficialPluginSummary(
      packageName = PluginPackageName(packageName),
      displayName = "Calendar",
      summary = null,
      family = "code-plugin",
      channel = null,
      iconUrl = null,
      latestVersion = null,
      ownerHandle = "openclaw",
      runtimeId = null,
      categories = listOf("Productivity"),
      topics = emptyList(),
      verificationTier = null,
      downloads = null,
      installs = null,
      stars = null,
      updatedAt = null,
    )
}

private class FakePluginCatalogRepository(
  private val recommended: OfficialPluginPage,
  private val search: List<OfficialPluginSearchMatch>,
) : PluginCatalogRepository {
  override suspend fun recommended(
    cursor: String?,
    limit: Int,
  ) = PluginCatalogResult.Success(recommended)

  override suspend fun search(
    query: String,
    limit: Int,
  ) = PluginCatalogResult.Success(search)

  override suspend fun detail(packageName: PluginPackageName): PluginCatalogResult<OfficialPluginDetail> = error("unused")

  override suspend fun readiness(packageName: PluginPackageName): PluginCatalogResult<PluginReadiness> = error("unused")

  override suspend fun versions(
    packageName: PluginPackageName,
    cursor: String?,
    limit: Int,
  ): PluginCatalogResult<PluginVersionPage> = error("unused")

  override suspend fun security(
    packageName: PluginPackageName,
    version: String,
  ): PluginCatalogResult<PluginSecurity> = error("unused")
}
