package ai.openclaw.app.ui.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillDirectoryModelsTest {
  @Test
  fun directoryPreservesRecommendedOrderAndRawNonEmptyCategories() {
    val first = skill("@openclaw/first", listOf("Development", "Research"))
    val second = skill("@openclaw/second", listOf("Development"))

    val projection = skillDirectoryPresentation(listOf(first, second))

    assertEquals(listOf(first, second), projection.recommended)
    assertEquals(listOf("Development", "Research"), projection.categories.map { it.name })
    assertEquals(listOf(first, second), projection.categories.first().skills)
    assertEquals(listOf(first), projection.categories.last().skills)
  }

  private fun skill(
    reference: String,
    categories: List<String>,
  ): SkillDirectoryItem =
    SkillDirectoryItem(
      reference = reference,
      displayName = reference,
      summary = null,
      version = null,
      categories = categories,
      installed = false,
      installedSkillKey = null,
      reviewing = false,
      installing = false,
    )
}
