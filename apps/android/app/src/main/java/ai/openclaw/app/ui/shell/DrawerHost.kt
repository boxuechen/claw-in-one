package ai.openclaw.app.ui.shell

import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Chat-first product shell. Material owns gestures, interruption, focus, scrim,
 * RTL, and system Back while product code owns only drawer content.
 */
@Composable
internal fun DrawerHost(
  drawerState: DrawerState,
  drawerContent: @Composable () -> Unit,
  content: @Composable () -> Unit,
) {
  ModalNavigationDrawer(
    drawerState = drawerState,
    gesturesEnabled = true,
    drawerContent = {
      ModalDrawerSheet(
        drawerState = drawerState,
        modifier =
          Modifier
            .fillMaxWidth(0.8f)
            .widthIn(max = 360.dp)
            .testTag("product-drawer"),
        drawerShape = RoundedCornerShape(topEnd = ClawTheme.radii.sheet, bottomEnd = ClawTheme.radii.sheet),
        drawerContainerColor = ClawTheme.colors.canvas,
        drawerContentColor = ClawTheme.colors.text,
      ) {
        drawerContent()
      }
    },
    content = content,
  )
}
