package ai.openclaw.app.ui.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PluginDirectoryModelsTest {
  @Test
  fun directoryPreservesFullRecommendedOrderAndRawNonEmptyCategories() {
    val first = plugin(id = "first", categories = listOf("Development", "Automation"))
    val second = plugin(id = "second", categories = listOf("Development"))

    val projection = pluginDirectoryPresentation(listOf(first, second))

    assertEquals(listOf(first, second), projection.recommended.items)
    assertEquals(
      listOf("Development", "Automation"),
      projection.categories.map { (it.id as PluginDirectorySectionId.Category).category },
    )
    assertEquals(listOf(first, second), projection.categories.first().items)
    assertEquals(listOf(first), projection.categories.last().items)
  }

  @Test
  fun sectionCapsPreviewAtSixAndPrecomputesOverflowContent() {
    val plugins = (1..10).map { index -> plugin(id = "plugin-$index") }

    val section = pluginDirectoryPresentation(plugins).recommended

    assertEquals(plugins, section.items)
    assertEquals(plugins.take(6), section.previewItems)
    assertEquals(4, section.overflow?.hiddenCount)
    assertEquals(plugins.drop(6).take(3), section.overflow?.iconItems)
    assertEquals(listOf("plugin-7", "plugin-8"), section.overflow?.labelNames)
    assertEquals(true, section.overflow?.hasMore)
  }

  @Test
  fun overflowDistinguishesOneTwoAndMoreHiddenItems() {
    val oneHidden = pluginDirectoryPresentation((1..7).map { plugin("p$it") }).recommended.overflow
    val twoHidden = pluginDirectoryPresentation((1..8).map { plugin("p$it") }).recommended.overflow
    val moreHidden = pluginDirectoryPresentation((1..9).map { plugin("p$it") }).recommended.overflow

    assertEquals(listOf("p7"), oneHidden?.labelNames)
    assertEquals(false, oneHidden?.hasMore)
    assertEquals(listOf("p7", "p8"), twoHidden?.labelNames)
    assertEquals(false, twoHidden?.hasMore)
    assertEquals(listOf("p7", "p8"), moreHidden?.labelNames)
    assertEquals(true, moreHidden?.hasMore)
  }

  @Test
  fun paginationStartsWithinThreeItemsOfTheLoadedEnd() {
    assertFalse(shouldLoadNextPluginPage(4, 8, canLoadNextPage = true, loadingNextPage = false))
    assertEquals(true, shouldLoadNextPluginPage(5, 8, canLoadNextPage = true, loadingNextPage = false))
    assertFalse(shouldLoadNextPluginPage(7, 8, canLoadNextPage = false, loadingNextPage = false))
    assertFalse(shouldLoadNextPluginPage(7, 8, canLoadNextPage = true, loadingNextPage = true))
  }

  @Test
  fun builtInsStayOutOfDirectoryAndInsideBuiltInManagement() {
    val bundled = plugin(id = "bundled", origin = "bundled", status = PluginInstallStatus.Ready)
    val official = plugin(id = "official", status = PluginInstallStatus.Installed)

    val projection = pluginDirectoryPresentation(listOf(bundled, official))

    assertFalse(bundled in projection.recommended.items)
    assertEquals(listOf(official), projection.installed)
    assertEquals(listOf(bundled), builtInPlugins(listOf(bundled, official)))
  }

  @Test
  fun productInternalPluginsStayOutOfEveryPluginSurface() {
    val bridge = plugin(id = "claw-in-one-android-developer-bridge", origin = "bundled", status = PluginInstallStatus.Ready)
    val vscreen = plugin(id = "claw-in-one-vscreen-foundation", origin = "bundled", status = PluginInstallStatus.Ready)

    val projection = pluginDirectoryPresentation(listOf(bridge, vscreen))

    assertEquals(emptyList<PluginCatalogItem>(), projection.recommended.items)
    assertEquals(emptyList<PluginCatalogItem>(), projection.installed)
    assertEquals(emptyList<PluginCatalogItem>(), builtInPlugins(listOf(bridge, vscreen)))
  }

  private fun plugin(
    id: String,
    categories: List<String> = listOf("Development"),
    origin: String = "clawhub",
    status: PluginInstallStatus = PluginInstallStatus.Available,
  ): PluginCatalogItem =
    PluginCatalogItem(
      pluginId = id,
      displayName = id,
      summary = null,
      origin = origin,
      categories = categories,
      packageName = "@openclaw/$id",
      version = null,
      kinds = listOf("code-plugin"),
      status = status,
      enabled = status == PluginInstallStatus.Ready,
      installSource = "clawhub",
      installReference = "@openclaw/$id",
      removable = status != PluginInstallStatus.Available,
      error = null,
    )
}
