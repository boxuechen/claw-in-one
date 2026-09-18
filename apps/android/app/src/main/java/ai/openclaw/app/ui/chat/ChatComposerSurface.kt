package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatCommandEntry
import ai.openclaw.app.i18n.NativeText
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.i18n.resolveNativeTextResource
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatComposer(
  value: String,
  onValueChange: (String) -> Unit,
  skillOptions: List<ChatSkillMentionOption>,
  skillsLoaded: Boolean,
  selectedSkillReferences: List<String>,
  staleSkillReferences: Set<String>,
  onSelectSkill: (ChatSkillMentionOption) -> Unit,
  onRemoveSkill: (String) -> Unit,
  attachments: List<PendingAttachment>,
  pendingRunCount: Int,
  shareStaging: Boolean,
  sendInFlight: Boolean,
  shareImportNotice: NativeText?,
  onDismissShareImportNotice: () -> Unit,
  commands: List<ChatCommandEntry>,
  onPickImages: () -> Unit,
  onPickDocument: () -> Unit,
  onRemoveAttachment: (String) -> Unit,
  onAbort: () -> Unit,
  onSend: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val slashCommands =
    remember(value, commands) {
      matchingSlashCommands(input = value, commands = commands)
    }
  val skillMentionQuery = remember(value) { chatSkillMentionQuery(value) }
  val matchingSkills = remember(value, skillOptions) { matchingChatSkillMentions(value, skillOptions) }

  // Offline sends queue durably too, so the gate is identical
  // to the connected one; admission errors keep the draft when the durable queue refuses it.
  val sendEnabled =
    chatComposerSendEnabled(
      pendingRunCount = pendingRunCount,
      hasContent = value.trim().isNotEmpty() || attachments.isNotEmpty(),
      shareStaging = shareStaging,
      sendInFlight = sendInFlight || staleSkillReferences.isNotEmpty(),
    )

  Column(modifier = modifier.fillMaxWidth().imePadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (shareImportNotice != null) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = shareImportNotice.resolveNativeTextResource(),
          style = ClawTheme.type.caption,
          color = ClawTheme.colors.warning,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismissShareImportNotice, modifier = Modifier.size(32.dp)) {
          Icon(Icons.Default.Close, contentDescription = nativeString("Dismiss shared-image warning"))
        }
      }
    }
    if (attachments.isNotEmpty()) {
      AttachmentStrip(attachments = attachments, onRemoveAttachment = onRemoveAttachment)
    }

    if (selectedSkillReferences.isNotEmpty()) {
      SkillMentionStrip(
        references = selectedSkillReferences,
        options = skillOptions,
        staleReferences = staleSkillReferences,
        onRemove = onRemoveSkill,
      )
    }

    if (staleSkillReferences.isNotEmpty()) {
      Text(
        text = nativeString("A selected Skill is no longer available. Remove it to continue."),
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.warning,
        modifier = Modifier.padding(horizontal = 8.dp).testTag("skill-mention-stale"),
      )
    }

    if (skillMentionQuery != null) {
      SkillMentionPanel(
        skills = matchingSkills,
        loaded = skillsLoaded,
        onSelect = onSelectSkill,
        modifier = Modifier.weight(1f, fill = false),
      )
    } else if (shouldShowSlashCommandMenu(value)) {
      SlashCommandPanel(
        commands = slashCommands,
        onSelect = { command -> onValueChange(slashCommandCompletion(command)) },
        // Reserve the editor and run controls before measuring suggestions.
        modifier = Modifier.weight(1f, fill = false),
      )
    }

    ChatInputPill(
      value = value,
      onValueChange = onValueChange,
      onPickImages = onPickImages,
      onPickDocument = onPickDocument,
      skillOptions = skillOptions,
      skillsLoaded = skillsLoaded,
      onSelectSkill = onSelectSkill,
      runActive = pendingRunCount > 0,
      onAbort = onAbort,
      sendEnabled = sendEnabled,
      onSend = onSend,
    )
  }
}

@Composable
private fun SkillMentionPanel(
  skills: List<ChatSkillMentionOption>,
  loaded: Boolean,
  onSelect: (ChatSkillMentionOption) -> Unit,
  modifier: Modifier,
) {
  ClawPanel(
    modifier = modifier.testTag("skill-mention-menu"),
    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
  ) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
      if (skills.isEmpty()) {
        Text(
          text = nativeString(if (loaded) "No Skills found" else "Loading Skills…"),
          style = ClawTheme.type.caption,
          color = ClawTheme.colors.textMuted,
          modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
        )
      } else {
        skills.forEachIndexed { index, skill ->
          Surface(
            onClick = { onSelect(skill) },
            color = Color.Transparent,
            contentColor = ClawTheme.colors.text,
            modifier = Modifier.testTag("skill-mention-${skill.reference}"),
          ) {
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .heightIn(min = ClawTheme.sizes.minimumTouchTarget)
                  .padding(horizontal = 10.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Text(
                text = listOfNotNull(skill.emoji, "@${skill.label}").joinToString(" "),
                style = ClawTheme.type.label,
                color = ClawTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(150.dp),
              )
              Text(
                text = skill.description.orEmpty(),
                style = ClawTheme.type.caption,
                color = ClawTheme.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
            }
          }
          if (index != skills.lastIndex) HorizontalDivider(color = ClawTheme.colors.border, thickness = 1.dp)
        }
      }
    }
  }
}

@Composable
private fun SkillMentionStrip(
  references: List<String>,
  options: List<ChatSkillMentionOption>,
  staleReferences: Set<String>,
  onRemove: (String) -> Unit,
) {
  val labels = remember(options) { options.associate { option -> option.reference to option.label } }
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("skill-mention-strip"),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    references.forEach { reference ->
      val label = labels[reference] ?: reference
      val removeDescription = nativeString("Remove Skill @\$skill", label)
      Surface(
        onClick = { onRemove(reference) },
        modifier =
          Modifier
            .heightIn(min = ClawTheme.sizes.minimumTouchTarget)
            .semantics { contentDescription = removeDescription }
            .testTag("skill-mention-chip-$reference"),
        shape = RoundedCornerShape(ClawTheme.radii.pill),
        color = ClawTheme.colors.surfaceRaised,
        contentColor = if (reference in staleReferences) ClawTheme.colors.warning else ClawTheme.colors.text,
        border = BorderStroke(1.dp, if (reference in staleReferences) ClawTheme.colors.warning else ClawTheme.colors.border),
      ) {
        Row(
          modifier = Modifier.padding(start = 11.dp, end = 7.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(text = "@$label", style = ClawTheme.type.label, maxLines = 1)
          Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlashCommandPanel(
  commands: List<ChatCommandEntry>,
  onSelect: (ChatCommandEntry) -> Unit,
  modifier: Modifier,
) {
  ClawPanel(modifier = modifier, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
      if (commands.isEmpty()) {
        Text(
          text = nativeString("No commands found"),
          style = ClawTheme.type.caption,
          color = ClawTheme.colors.textMuted,
          modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
        )
      } else {
        commands.forEachIndexed { index, command ->
          SlashCommandRow(command = command, onClick = { onSelect(command) })
          if (index != commands.lastIndex) {
            HorizontalDivider(color = ClawTheme.colors.border, thickness = 1.dp)
          }
        }
      }
    }
  }
}

@Composable
private fun SlashCommandRow(
  command: ChatCommandEntry,
  onClick: () -> Unit,
) {
  Surface(onClick = onClick, color = Color.Transparent, contentColor = ClawTheme.colors.text) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .heightIn(min = ClawTheme.sizes.minimumTouchTarget)
          .padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = slashCommandText(command),
        style = ClawTheme.type.label,
        color = ClawTheme.colors.text,
        modifier = Modifier.width(82.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
          text = command.description.ifBlank { command.category ?: nativeString("Command") },
          style = ClawTheme.type.caption,
          color = ClawTheme.colors.textMuted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun ChatInputPill(
  value: String,
  onValueChange: (String) -> Unit,
  onPickImages: () -> Unit,
  onPickDocument: () -> Unit,
  skillOptions: List<ChatSkillMentionOption>,
  skillsLoaded: Boolean,
  onSelectSkill: (ChatSkillMentionOption) -> Unit,
  runActive: Boolean,
  onAbort: () -> Unit,
  sendEnabled: Boolean,
  onSend: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val hardwareEnterHandler = remember { PhysicalChatSendKeyHandler() }
  var optionsMenuExpanded by rememberSaveable { mutableStateOf(false) }
  var optionsMenuPage by rememberSaveable { mutableStateOf(ChatComposerOptionsPage.Root) }

  fun closeOptionsMenu() {
    optionsMenuExpanded = false
    optionsMenuPage = ChatComposerOptionsPage.Root
  }

  Surface(
    modifier = modifier.heightIn(min = ClawTheme.sizes.minimumTouchTarget),
    shape = RoundedCornerShape(ClawTheme.radii.pill),
    color = ClawTheme.colors.surface,
    contentColor = ClawTheme.colors.text,
    border = BorderStroke(1.dp, ClawTheme.colors.borderStrong.copy(alpha = 0.72f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
      verticalAlignment = Alignment.Bottom,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Box {
        val optionsDescription = nativeString("Composer options")
        Surface(
          onClick = {
            optionsMenuPage = ChatComposerOptionsPage.Root
            optionsMenuExpanded = true
          },
          modifier =
            Modifier
              .size(ClawTheme.sizes.minimumTouchTarget)
              .semantics { contentDescription = optionsDescription },
          shape = CircleShape,
          color = Color.Transparent,
          contentColor = ClawTheme.colors.text,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(ClawTheme.sizes.standardIcon))
          }
        }
        DropdownMenu(
          expanded = optionsMenuExpanded,
          onDismissRequest = ::closeOptionsMenu,
          modifier = Modifier.width(248.dp).testTag("chat-composer-options-menu"),
          shape = RoundedCornerShape(ClawTheme.radii.panel),
          containerColor = ClawTheme.colors.floatingSurface,
          tonalElevation = 0.dp,
          shadowElevation = 0.dp,
        ) {
          when (optionsMenuPage) {
            ChatComposerOptionsPage.Root -> {
              ComposerOptionsMenuItem(
                label = nativeString("Photos"),
                icon = Icons.Default.Photo,
                onClick = {
                  closeOptionsMenu()
                  onPickImages()
                },
              )
              ComposerOptionsMenuItem(
                label = nativeString("Files"),
                icon = Icons.Default.AttachFile,
                onClick = {
                  closeOptionsMenu()
                  onPickDocument()
                },
              )
              HorizontalDivider(color = ClawTheme.colors.border)
              ComposerOptionsMenuItem(
                label = nativeString("Skills"),
                icon = Icons.Default.Build,
                testTag = "chat-composer-open-skills",
                onClick = { optionsMenuPage = ChatComposerOptionsPage.Skills },
              )
            }
            ChatComposerOptionsPage.Skills -> {
              ComposerOptionsMenuItem(
                label = nativeString("Skills"),
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                testTag = "chat-composer-skills-back",
                onClick = { optionsMenuPage = ChatComposerOptionsPage.Root },
              )
              HorizontalDivider(color = ClawTheme.colors.border)
              when {
                !skillsLoaded ->
                  Text(
                    nativeString("Loading Skills…"),
                    style = ClawTheme.type.caption,
                    color = ClawTheme.colors.textMuted,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                  )
                skillOptions.isEmpty() ->
                  Text(
                    nativeString("No Skills available"),
                    style = ClawTheme.type.caption,
                    color = ClawTheme.colors.textMuted,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                  )
                else ->
                  skillOptions.forEach { skill ->
                    DropdownMenuItem(
                      modifier = Modifier.heightIn(min = ClawTheme.sizes.minimumTouchTarget).testTag("chat-composer-skill-${skill.reference}"),
                      contentPadding = PaddingValues(horizontal = ClawTheme.spacing.xs),
                      text = {
                        Text(
                          text = listOfNotNull(skill.emoji, skill.label).joinToString(" "),
                          style = ClawTheme.type.body,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                        )
                      },
                      onClick = {
                        closeOptionsMenu()
                        onSelectSkill(skill)
                      },
                    )
                  }
              }
            }
          }
        }
      }
      Box(modifier = Modifier.weight(1f)) {
        ChatTextFieldValueAdapter(
          value = value,
          onValueChange = onValueChange,
          keyHandler = hardwareEnterHandler,
        ) { textFieldValue, updateTextFieldValue ->
          BasicTextField(
            value = textFieldValue,
            onValueChange = updateTextFieldValue,
            textStyle = ClawTheme.type.body.copy(color = ClawTheme.colors.text),
            cursorBrush = SolidColor(ClawTheme.colors.primary),
            minLines = 1,
            maxLines = 4,
            modifier =
              Modifier
                .fillMaxWidth()
                .onPreInterceptKeyBeforeSoftKeyboard { event ->
                  hardwareEnterHandler.handle(
                    event = event,
                    sendEnabled = sendEnabled,
                    textEmpty = textFieldValue.text.isEmpty(),
                    compositionActive = textFieldValue.composition != null,
                    onSend = onSend,
                  )
                },
            decorationBox = { innerTextField ->
              Box(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .heightIn(min = ClawTheme.sizes.minimumTouchTarget),
                contentAlignment = Alignment.CenterStart,
              ) {
                if (value.isEmpty()) {
                  Text(
                    text = nativeString("Ask OpenClaw anything"),
                    style = ClawTheme.type.body,
                    color = ClawTheme.colors.textSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                innerTextField()
              }
            },
          )
        }
      }
      when (resolveChatComposerTrailingAction(runActive = runActive, sendEnabled = sendEnabled)) {
        ChatComposerTrailingAction.Send -> SendButton(enabled = true, onClick = onSend)
        ChatComposerTrailingAction.Stop -> StopButton(onClick = onAbort)
        ChatComposerTrailingAction.None -> Unit
      }
    }
  }
}

private enum class ChatComposerOptionsPage {
  Root,
  Skills,
}

@Composable
private fun ComposerOptionsMenuItem(
  label: String,
  icon: ImageVector,
  onClick: () -> Unit,
  testTag: String? = null,
) {
  DropdownMenuItem(
    modifier =
      Modifier
        .heightIn(min = ClawTheme.sizes.minimumTouchTarget)
        .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
    contentPadding = PaddingValues(horizontal = ClawTheme.spacing.xs),
    text = { Text(label, style = ClawTheme.type.body) },
    leadingIcon = { AttachmentMenuIcon(icon) },
    onClick = onClick,
  )
}

@Composable
private fun AttachmentMenuIcon(icon: ImageVector) {
  Surface(
    modifier = Modifier.size(40.dp),
    shape = CircleShape,
    color = ClawTheme.colors.surfacePressed,
    contentColor = ClawTheme.colors.text,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ClawTheme.sizes.standardIcon))
    }
  }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
  val stopDescription = nativeString("Stop")
  Surface(
    onClick = onClick,
    modifier =
      Modifier
        .size(ClawTheme.sizes.minimumTouchTarget)
        .semantics { contentDescription = stopDescription },
    shape = CircleShape,
    color = ClawTheme.colors.primary,
    contentColor = ClawTheme.colors.primaryText,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(imageVector = Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(ClawTheme.sizes.standardIcon))
    }
  }
}

@Composable
private fun AttachmentStrip(
  attachments: List<PendingAttachment>,
  onRemoveAttachment: (String) -> Unit,
) {
  Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    attachments.forEach { attachment ->
      AttachmentChip(attachment = attachment, onRemove = { onRemoveAttachment(attachment.id) })
    }
  }
}

@Composable
private fun AttachmentChip(
  attachment: PendingAttachment,
  onRemove: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(ClawTheme.radii.pill),
    color = ClawTheme.colors.surfaceRaised,
    contentColor = ClawTheme.colors.text,
    border = BorderStroke(1.dp, ClawTheme.colors.border),
  ) {
    Row(
      modifier = Modifier.padding(start = 9.dp, top = 5.dp, end = 5.dp, bottom = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = attachment.fileName,
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      val removeDescription = nativeString("Remove attachment")
      Surface(
        onClick = onRemove,
        modifier =
          Modifier
            .size(ClawTheme.sizes.minimumTouchTarget)
            .semantics { contentDescription = removeDescription },
        shape = CircleShape,
        color = ClawTheme.colors.canvas,
        contentColor = ClawTheme.colors.text,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(13.dp))
        }
      }
    }
  }
}

@Composable
private fun SendButton(
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val sendDescription = nativeString("Send")
  Surface(
    onClick = onClick,
    enabled = enabled,
    modifier =
      Modifier
        .size(ClawTheme.sizes.minimumTouchTarget)
        .semantics { contentDescription = sendDescription },
    shape = CircleShape,
    color = if (enabled) ClawTheme.colors.primary else ClawTheme.colors.surfacePressed,
    contentColor = if (enabled) ClawTheme.colors.primaryText else ClawTheme.colors.textSubtle,
    border = BorderStroke(1.dp, if (enabled) ClawTheme.colors.primary else ClawTheme.colors.border),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
    }
  }
}
