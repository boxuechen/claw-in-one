package ai.openclaw.app.mcp

/**
 * Android-owned snapshot of OpenClaw v2026.8.1 `ui/src/pages/plugins/presentation.ts`.
 * The sibling OpenClaw checkout is reference-only and is never a build dependency.
 */
internal val pinnedConnectorSuggestions: List<McpConnectorSuggestion> =
  listOf(
    mcp("notion", "Notion", "Search, create, and update pages and databases in your Notion workspace.", McpConnectorGroup.Work, "https://mcp.notion.com/mcp", McpConnectorFollowUp.OAuth, "https://developers.notion.com/docs/mcp", auth = "oauth"),
    mcp("linear", "Linear", "Triage issues, update cycles, and file bugs straight from chat.", McpConnectorGroup.Work, "https://mcp.linear.app/mcp", McpConnectorFollowUp.OAuth, "https://linear.app/docs/mcp", auth = "oauth"),
    mcp("todoist", "Todoist", "Read, add, and complete tasks and projects in Todoist.", McpConnectorGroup.Work, "https://ai.todoist.net/mcp", McpConnectorFollowUp.OAuth, "https://www.todoist.com/help/articles/use-claude-code-with-todoist-cli-and-mcp-b1USJ4HB3", auth = "oauth"),
    mcp("airtable", "Airtable", "Query and update records, tables, and bases in Airtable.", McpConnectorGroup.Work, "https://mcp.airtable.com/mcp", McpConnectorFollowUp.OAuth, "https://support.airtable.com/docs/using-the-airtable-mcp-server", auth = "oauth"),
    search("jira", "Jira", "Create, search, and triage Jira tickets from chat.", McpConnectorGroup.Work, "jira"),
    mcp("canva", "Canva", "Create and edit Canva designs, manage assets, and export results.", McpConnectorGroup.Work, "https://mcp.canva.com/mcp", McpConnectorFollowUp.OAuth, "https://www.canva.dev/docs/mcp/", auth = "oauth"),
    mcp("stripe", "Stripe", "Check payments, customers, invoices, and subscriptions in your Stripe account.", McpConnectorGroup.Work, "https://mcp.stripe.com", McpConnectorFollowUp.OAuth, "https://docs.stripe.com/mcp", auth = "oauth"),
    search("google-calendar", "Calendar", "Read, create, and get briefed on events — your agent owns your schedule.", McpConnectorGroup.Work, "google calendar"),
    search("email-inbox", "Email", "Mailbox triage, summaries, and drafts with send-on-approval.", McpConnectorGroup.Work, "email"),
    search("pdf-tools", "PDF", "Extract, merge, convert, and OCR PDF documents.", McpConnectorGroup.Work, "pdf"),
    search("transcription", "Transcription", "Turn audio and video into clean, structured transcripts.", McpConnectorGroup.Work, "transcription"),
    mcp("github", "GitHub", "PR review queues, issue triage, and repo Q&A through the official GitHub MCP.", McpConnectorGroup.Dev, "https://api.githubcopilot.com/mcp/", McpConnectorFollowUp.Endpoint, "https://docs.github.com/en/copilot/how-tos/provide-context/use-mcp-in-your-ide/use-the-github-mcp-server"),
    mcp("sentry", "Sentry", "Crash alerts explained and triaged the moment they fire.", McpConnectorGroup.Dev, "https://mcp.sentry.dev/mcp", McpConnectorFollowUp.OAuth, "https://mcp.sentry.dev/", auth = "oauth"),
    mcp("context7", "Context7", "Version-specific library docs and code examples while coding. No signup needed.", McpConnectorGroup.Dev, "https://mcp.context7.com/mcp", McpConnectorFollowUp.None, "https://github.com/upstash/context7"),
    mcp("deepwiki", "DeepWiki", "Ask questions about any public GitHub repo. Free, no account needed.", McpConnectorGroup.Dev, "https://mcp.deepwiki.com/mcp", McpConnectorFollowUp.None, "https://docs.devin.ai/work-with-devin/deepwiki-mcp"),
    mcp("hugging-face", "Hugging Face", "Search models, datasets, and papers; run Spaces as tools.", McpConnectorGroup.Dev, "https://huggingface.co/mcp", McpConnectorFollowUp.None, "https://huggingface.co/docs/hub/hf-mcp-server"),
    search("grafana", "Grafana", "Grafana know-how and community connectors for dashboards and alerts.", McpConnectorGroup.Dev, "grafana"),
    search("kubernetes", "Kubernetes", "Cluster operations and troubleshooting from chat.", McpConnectorGroup.Dev, "kubernetes"),
    mcp("home-assistant", "Home Assistant", "Control lights, climate, and automations across your whole home.", McpConnectorGroup.Home, "http://homeassistant.local:8123/api/mcp", McpConnectorFollowUp.Endpoint, "https://www.home-assistant.io/integrations/mcp_server/"),
    search("spotify", "Spotify", "Search, queue, and soundtrack your day with mood-based playlists.", McpConnectorGroup.Home, "spotify"),
    search("sonos", "Sonos", "Whole-home audio: play, group rooms, and queue by chat.", McpConnectorGroup.Home, "sonos"),
    search("reddit", "Reddit", "Browse, search, and summarize subreddits and threads.", McpConnectorGroup.Life, "reddit"),
    search("portfolio-pulse", "Markets", "Live stocks and crypto with price alerts and daily digests.", McpConnectorGroup.Life, "stocks"),
    search("trip-scout", "Travel", "Flight and hotel search with fare watching and trip memory.", McpConnectorGroup.Life, "flights"),
    search("morning-brief", "News", "A personalized daily briefing: news, weather, and tasks in one message.", McpConnectorGroup.Life, "news"),
    search("maps", "Maps", "Places, routing, and travel-time answers.", McpConnectorGroup.Life, "maps"),
    search("translation", "Translation", "Translate and localize text and documents.", McpConnectorGroup.Life, "translation"),
    search("notes", "Notes", "Capture notes to Markdown, Obsidian, Notion, or Bear.", McpConnectorGroup.Life, "notes"),
  )

private fun mcp(
  id: String,
  name: String,
  description: String,
  group: McpConnectorGroup,
  url: String,
  followUp: McpConnectorFollowUp,
  docsUrl: String,
  auth: String? = null,
): McpConnectorSuggestion =
  McpConnectorSuggestion(
    id = id,
    name = name,
    description = description,
    group = group,
    action =
      McpConnectorAction.AddMcp(
        McpServerTemplate(
          serverName = id,
          url = url,
          transport = McpServerTransport.StreamableHttp,
          auth = auth,
          followUp = followUp,
          docsUrl = docsUrl,
        ),
      ),
  )

private fun search(
  id: String,
  name: String,
  description: String,
  group: McpConnectorGroup,
  query: String,
): McpConnectorSuggestion =
  McpConnectorSuggestion(
    id = id,
    name = name,
    description = description,
    group = group,
    action = McpConnectorAction.SearchClawHub(query),
  )
