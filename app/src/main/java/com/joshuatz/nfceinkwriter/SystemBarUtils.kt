package com.joshuatz.nfceinkwriter

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

object SystemBarUtils {

    private val topInsetTypes = WindowInsetsCompat.Type.statusBars() or
        WindowInsetsCompat.Type.displayCutout()

    /** Push toolbars below the status bar on edge-to-edge OEM builds (incl. One UI 9 beta). */
    fun applyStatusBarInset(toolbarHost: View) {
        val activity = toolbarHost.context as? Activity ?: return
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        ViewCompat.setOnApplyWindowInsetsListener(toolbarHost) { view, windowInsets ->
            val top = topInset(windowInsets, view)
            view.updatePadding(top = top)
            windowInsets
        }
        ViewCompat.requestApplyInsets(toolbarHost)

        // One UI beta builds sometimes deliver insets after the first layout pass.
        toolbarHost.post {
            val applied = ViewCompat.getRootWindowInsets(toolbarHost)?.let { topInset(it, toolbarHost) } ?: 0
            if (applied > toolbarHost.paddingTop) {
                toolbarHost.updatePadding(top = applied)
            } else if (toolbarHost.paddingTop == 0) {
                toolbarHost.updatePadding(top = fallbackStatusBarHeight(toolbarHost))
            }
        }
    }

    /** Keep scrollable content above the gesture/nav bar. */
    fun applyNavigationBarInset(content: View) {
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, windowInsets ->
            val nav = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = nav.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(content)
    }

    private fun topInset(windowInsets: WindowInsetsCompat, view: View): Int {
        val reported = windowInsets.getInsets(topInsetTypes).top
        return if (reported > 0) reported else fallbackStatusBarHeight(view)
    }

    private fun fallbackStatusBarHeight(view: View): Int {
        val resId = view.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) view.resources.getDimensionPixelSize(resId) else 0
    }
}
