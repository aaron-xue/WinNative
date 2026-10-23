package com.winlator.cmod.feature.retro

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.shared.android.DirectoryPickerDialog
import com.winlator.cmod.shared.ui.dialog.WinNativeComposeDialogs
import com.winlator.cmod.shared.ui.nav.paneNavItem
import java.io.File
import kotlinx.coroutines.launch

/**
 * The "Nintendo Switch" settings sub-screen, extracted from RetroDefaultsScreen so that
 * file stays small. Rendered inside the drill-down menu when the Switch console is
 * selected. Owns its own refresh state and the Switch key/firmware importers.
 *
 * @param onManageSaves opens the Citron save manager. Kept as a callback so the overlay
 *   stays owned by RetroDefaultsScreen and covers the whole screen.
 */
@Composable
internal fun RetroSwitchScreen(onManageSaves: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }

    val importSwitchKeys = {
        val activity = context as? android.app.Activity
        if (activity != null) {
            DirectoryPickerDialog.showFile(
                activity = activity,
                title = context.getString(R.string.retro_scr_import_switch_keys),
                allowedExtensions = setOf("keys"),
            ) { path ->
                scope.launch {
                    val result =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            RetroBiosImport.importSwitchKeysFromUri(context, android.net.Uri.fromFile(File(path)))
                        }
                    result
                        .onSuccess { Toast.makeText(context, context.getString(R.string.retro_scr_switch_keys_imported, it), Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.retro_scr_invalid_switch_keys_file), Toast.LENGTH_LONG).show() }
                    refresh++
                }
            }
        }
    }

    val importSwitchFirmware = {
        val activity = context as? android.app.Activity
        if (activity != null) {
            DirectoryPickerDialog.showFile(
                activity = activity,
                title = context.getString(R.string.retro_scr_import_switch_firmware),
                allowedExtensions = setOf("zip"),
            ) { path ->
                scope.launch {
                    val loading =
                        WinNativeComposeDialogs.showLoading(
                            context,
                            context.getString(R.string.retro_scr_importing_switch_firmware),
                        )
                    val result =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            RetroBiosImport.importSwitchFirmwareFromUri(context, android.net.Uri.fromFile(File(path)))
                        }
                    loading?.dismiss()
                    result
                        .onSuccess {
                            Toast.makeText(context, context.getString(R.string.retro_scr_switch_firmware_imported, it), Toast.LENGTH_SHORT).show()
                            if (RetroBiosImport.installedSwitchKeys(context).isEmpty()) {
                                Toast.makeText(context, context.getString(R.string.retro_scr_switch_firmware_keys_warning), Toast.LENGTH_LONG).show()
                            }
                        }
                        .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.retro_scr_invalid_switch_firmware_file), Toast.LENGTH_LONG).show() }
                    refresh++
                }
            }
        }
    }

    @Suppress("UNUSED_EXPRESSION") refresh

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- Keys ----
        RetroSettingGroup {
            RetroGroupTitle(stringResource(R.string.retro_scr_nintendo_switch_keys))
            val switchKeys = RetroBiosImport.installedSwitchKeys(context)
            RetroInfoRow(
                stringResource(R.string.retro_scr_installed),
                if (switchKeys.isEmpty()) {
                    stringResource(R.string.retro_scr_none_switch_keys)
                } else {
                    switchKeys.joinToString(", ")
                },
            )
            Button(
                onClick = { runCatching { importSwitchKeys() } },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .paneNavItem(
                            cornerRadius = 8.dp,
                            onActivate = { runCatching { importSwitchKeys() } },
                            highlightColor = Color(0xFF4FC3F7),
                            tapToSelect = true,
                        ),
            ) {
                Text(stringResource(R.string.retro_scr_import_switch_keys))
            }
        }

        // ---- Firmware ----
        RetroSettingGroup {
            RetroGroupTitle(stringResource(R.string.retro_scr_nintendo_switch_firmware))
            val fwCount = RetroBiosImport.installedSwitchFirmwareCount(context)
            RetroInfoRow(
                stringResource(R.string.retro_scr_installed),
                if (fwCount == 0) {
                    stringResource(R.string.retro_scr_no_switch_firmware)
                } else {
                    context.getString(R.string.retro_scr_switch_firmware_installed, fwCount)
                },
            )
            Button(
                onClick = { runCatching { importSwitchFirmware() } },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .paneNavItem(
                            cornerRadius = 8.dp,
                            onActivate = { runCatching { importSwitchFirmware() } },
                            highlightColor = Color(0xFF4FC3F7),
                            tapToSelect = true,
                        ),
            ) {
                Text(stringResource(R.string.retro_scr_import_switch_firmware))
            }
        }

        // ---- GPU driver ----
        RetroSettingGroup {
            val switchDrivers = remember { com.armsx2.CustomDriver.listInstalled(context) }
            val citronPrefs = remember { context.getSharedPreferences("CITRON", android.content.Context.MODE_PRIVATE) }
            val driverIds = remember(switchDrivers) { listOf("") + switchDrivers.map { it.id } }
            val driverLabels = listOf(stringResource(R.string.retro_gpu_driver_system)) + switchDrivers.map { it.name }
            val curDriver = citronPrefs.getString("wn.switch.driver", "") ?: ""
            RetroSettingDropdown(
                stringResource(R.string.retro_gpu_driver),
                driverLabels,
                driverIds.indexOf(curDriver).coerceAtLeast(0),
            ) { idx ->
                val id = driverIds[idx]
                if (id.isEmpty()) {
                    citronPrefs.edit()
                        .remove("wn.switch.driver")
                        .remove("wn.switch.driverDir")
                        .remove("wn.switch.driverName")
                        .remove("wn.switch.driverRedirect")
                        .apply()
                } else {
                    switchDrivers.firstOrNull { it.id == id }?.let { d ->
                        citronPrefs.edit()
                            .putString("wn.switch.driver", id)
                            .putString("wn.switch.driverDir", d.driverDir.absolutePath + "/")
                            .putString("wn.switch.driverName", d.libraryName)
                            .putString("wn.switch.driverRedirect", d.redirectDir.absolutePath + "/")
                            .apply()
                    }
                }
                refresh++
            }
            if (switchDrivers.isEmpty()) {
                Text(
                    stringResource(R.string.retro_gpu_driver_hint),
                    color = PageSub,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // ---- Saves ----
        RetroSettingGroup {
            RetroGroupTitle(stringResource(R.string.retro_scr_switch_saves))
            RetroInfoRow(
                stringResource(R.string.retro_scr_switch_saves_hint_title),
                stringResource(R.string.retro_scr_switch_saves_hint),
            )
            Button(
                onClick = onManageSaves,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .paneNavItem(
                            cornerRadius = 8.dp,
                            onActivate = onManageSaves,
                            highlightColor = Color(0xFF4FC3F7),
                            tapToSelect = true,
                        ),
            ) {
                Text(stringResource(R.string.retro_scr_manage_switch_saves))
            }
        }
    }
}
