package ai.openclaw.app.ui.sidebar

import ai.openclaw.app.approval.ApprovalSession
import ai.openclaw.app.chat.ChatSessionEntry
import ai.openclaw.app.project.ProjectGroup
import ai.openclaw.app.project.ProjectRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarScreenTest {
  @Test
  fun primaryDestinationsFollowTheFixedProductOrder() {
    assertEquals(
      listOf(
        SidebarDestination.DevKit,
        SidebarDestination.Plugins,
        SidebarDestination.VScreen,
        SidebarDestination.Terminal,
      ),
      sidebarPrimaryDestinations,
    )
  }

  @Test
  fun projectsAndTheirChatsSortByLatestActivity() {
    val projects =
      sidebarProjects(
        groups =
          listOf(
            group("older", "Older", chat("older-1", "older", 10)),
            group("empty", "Empty"),
            group("newer", "Newer", chat("newer-1", "newer", 20), chat("newer-2", "newer", 30)),
          ),
        activeSessionKey = "newer-1",
        collapsedProjectIds = emptySet(),
        query = "",
        approvalCounts = emptyMap(),
      )

    assertEquals(listOf("newer", "older", "empty"), projects.map(SidebarProjectUi::id))
    assertEquals(listOf("newer-2", "newer-1"), projects.first().chats.map { it.session.key })
    assertTrue(
      projects
        .first()
        .chats
        .last()
        .selected,
    )
  }

  @Test
  fun searchRevealsMatchingChildrenWithoutChangingCollapsedInput() {
    val groups =
      listOf(
        group(
          "demo",
          "Demo Project",
          chat("kotlin", "demo", 1, "Kotlin screen"),
          chat("keyboard", "demo", 2, "Fix keyboard"),
        ),
        group("other", "Other", chat("backend", "other", 3, "Backend")),
      )
    val collapsed = setOf("demo")
    val search = sidebarProjects(groups, "", collapsed, "keyboard", emptyMap())
    val restored = sidebarProjects(groups, "", collapsed, "", emptyMap())

    assertEquals(listOf("demo"), search.map(SidebarProjectUi::id))
    assertEquals(listOf("keyboard"), search.single().chats.map { it.session.key })
    assertTrue(search.single().expanded)
    assertFalse(restored.first { it.id == "demo" }.expanded)
  }

  @Test
  fun approvalAndSessionTitleRemainAttachedToTheExactChat() {
    val session = chat("agent:main:claw-in-one:demo", "demo", 5, "Named by Gateway")
    val approval = ApprovalSession(session.key, session.ownerAgentId)
    val project =
      sidebarProjects(
        groups = listOf(group("demo", "Demo", session)),
        activeSessionKey = session.key,
        collapsedProjectIds = emptySet(),
        query = "",
        approvalCounts = mapOf(approval to 2),
      ).single()

    assertEquals("Named by Gateway", sidebarSessionTitle(session))
    assertEquals(2, project.chats.single().approvalCount)
    assertTrue(project.chats.single().selected)
  }

  private fun group(
    id: String,
    name: String,
    vararg chats: ChatSessionEntry,
  ) = ProjectGroup(ProjectRecord(id, name, "/tmp/$id", "registered"), chats.toList())

  private fun chat(
    key: String,
    projectId: String,
    activity: Long,
    title: String = key,
  ) = ChatSessionEntry(
    key = key,
    projectId = projectId,
    updatedAtMs = activity,
    lastActivityAt = activity,
    displayName = title,
  )
}
