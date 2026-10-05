package expo.modules.launchsystembars

import android.os.Build
import androidx.core.view.WindowCompat
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * Lets the cold-start launch animation (src/components/LaunchAnimation.tsx) run edge to edge on its
 * dark field. With 3-button navigation, Android draws a light contrast scrim behind the buttons by
 * default (measured as a #E6E8E8 band across the bottom of the graphite field). This turns that
 * scrim off and the buttons light while the animation is up, and restores the window exactly as it
 * found it afterwards. It is deliberately not app-wide: everywhere else the system's contrast
 * protection keeps the buttons readable over whatever the app draws.
 *
 * Android 10 (API 29) and up only. Below that there is no contrast scrim to turn off: React
 * Native's edge-to-edge (WindowUtil.enableEdgeToEdge) gives the bar a colour of its own instead,
 * 90% white in light mode, and light buttons on that would be close to invisible. Those versions
 * keep the system's own bar during the animation.
 */
class LaunchSystemBarsModule : Module() {
  // What the window had before enterDarkField, so restore puts back exactly that. Null when nothing
  // is to be restored, which makes both functions safe to call more than once.
  private var saved: Saved? = null

  private data class Saved(val contrastEnforced: Boolean, val lightNavigationBars: Boolean)

  override fun definition() = ModuleDefinition {
    Name("LaunchSystemBars")

    AsyncFunction("enterDarkField") {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@AsyncFunction null
      val window = appContext.currentActivity?.window ?: return@AsyncFunction null
      val controller = WindowCompat.getInsetsController(window, window.decorView)
      if (saved == null) {
        saved = Saved(
          contrastEnforced = window.isNavigationBarContrastEnforced,
          lightNavigationBars = controller.isAppearanceLightNavigationBars,
        )
      }
      window.isNavigationBarContrastEnforced = false
      controller.isAppearanceLightNavigationBars = false
      null
    }.runOnQueue(Queues.MAIN)

    AsyncFunction("restore") {
      val previous = saved ?: return@AsyncFunction null
      // Cleared only once there is a window to restore, so a later call can still put it back.
      val window = appContext.currentActivity?.window ?: return@AsyncFunction null
      saved = null
      val controller = WindowCompat.getInsetsController(window, window.decorView)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = previous.contrastEnforced
      }
      controller.isAppearanceLightNavigationBars = previous.lightNavigationBars
      null
    }.runOnQueue(Queues.MAIN)
  }
}
