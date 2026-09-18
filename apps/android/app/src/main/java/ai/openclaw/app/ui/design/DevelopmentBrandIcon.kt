package ai.openclaw.app.ui.design

import ai.openclaw.app.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp

internal enum class DevelopmentBrand {
  Android,
  Kotlin,
  Flutter,
  Godot,
  ReactNative,
}

@Composable
internal fun DevelopmentBrandIcon(
  brand: DevelopmentBrand,
  size: Dp,
  modifier: Modifier = Modifier,
) {
  val drawableRes =
    when (brand) {
      DevelopmentBrand.Android -> R.drawable.devkit_brand_android
      DevelopmentBrand.Kotlin -> R.drawable.devkit_brand_kotlin
      DevelopmentBrand.Flutter -> R.drawable.devkit_brand_flutter
      DevelopmentBrand.Godot -> R.drawable.devkit_brand_godot
      DevelopmentBrand.ReactNative -> R.drawable.devkit_brand_react_native
    }
  Image(
    painter = painterResource(drawableRes),
    contentDescription = null,
    contentScale = ContentScale.Fit,
    modifier = modifier.then(Modifier.size(size)),
  )
}
