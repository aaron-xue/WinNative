package com.winlator.cmod.runtime.display

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.ui.controllertest.ControllerTestDialog

object ControllerTestHost {
    @JvmStatic
    fun attach(
        view: ComposeView,
        activity: XServerDisplayActivity,
        onDismiss: Runnable,
    ) {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            WinNativeTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Color(0xFF1A9FFF),
                        background = Color(0xFF18181D),
                        surface = Color(0xFF1C1C2A),
                    ),
            ) {
                ControllerTestDialog(
                    onDismiss = { onDismiss.run() },
                )
            }
        }
    }
}