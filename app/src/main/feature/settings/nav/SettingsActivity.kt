package com.winlator.cmod.feature.settings.nav

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.winlator.cmod.feature.settings.SettingsHost
import com.winlator.cmod.feature.settings.SettingsNavBridge
import com.winlator.cmod.feature.settings.SettingsNavItem

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bridge = SettingsNavBridge()
        val startItem = intent.getSerializableExtra("startItem") as? SettingsNavItem
            ?: SettingsNavItem.CONTAINERS
        val profileId = intent.getIntExtra("profileId", 0)

        setContent {
            SettingsHost(
                bridge = bridge,
                startItem = startItem,
                selectedProfileId = profileId,
                bordersPaused = false,
                onBack = { finish() },
            )
        }
    }
}