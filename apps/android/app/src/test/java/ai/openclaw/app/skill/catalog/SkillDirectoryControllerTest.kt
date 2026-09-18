package ai.openclaw.app.skill.catalog

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkillDirectoryControllerTest {
  @Test
  fun refreshAndSearchPublishIndependentImmutableDirectoryState() =
    runTest {
      val skill = skill("calendar")
      val controller =
        SkillDirectoryController(
          scope = this,
          repository =
            FakeSkillCatalogRepository(
              recommended = OfficialSkillPage(listOf(skill), nextCursor = "next"),
              search = listOf(OfficialSkillSearchMatch(skill, score = 1.0)),
            ),
        )

      controller.refresh()
      runCurrent()
      controller.search(" calendar ")
      runCurrent()

      assertEquals(listOf(skill), controller.state.value.items)
      assertEquals("next", controller.state.value.nextCursor)
      assertEquals("calendar", controller.state.value.query)
      assertEquals(
        listOf(skill),
        controller.state.value.searchResults
          .map { it.skill },
      )
      assertFalse(controller.state.value.refreshing)
      assertFalse(controller.state.value.searching)
    }

  private fun skill(slug: String): OfficialSkillSummary =
    OfficialSkillSummary(
      identity = OfficialSkillIdentity("openclaw", slug),
      displayName = "Calendar",
      summary = null,
      channel = null,
      iconUrl = null,
      latestVersion = null,
      categories = listOf("Productivity"),
      topics = emptyList(),
      verificationTier = null,
      downloads = null,
      installs = null,
      stars = null,
      updatedAt = null,
    )
}

private class FakeSkillCatalogRepository(
  private val recommended: OfficialSkillPage,
  private val search: List<OfficialSkillSearchMatch>,
) : SkillCatalogRepository {
  override suspend fun recommended(
    cursor: String?,
    limit: Int,
  ) = SkillCatalogResult.Success(recommended)

  override suspend fun search(
    query: String,
    limit: Int,
  ) = SkillCatalogResult.Success(search)

  override suspend fun detail(identity: OfficialSkillIdentity): SkillCatalogResult<OfficialSkillDetail> = error("unused")

  override suspend fun versions(
    identity: OfficialSkillIdentity,
    cursor: String?,
    limit: Int,
  ): SkillCatalogResult<SkillVersionPage> = error("unused")
}
