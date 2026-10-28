package com.winlator.cmod.feature.retro

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import org.citron.citron_emu.wn.SwitchLocaleDefaults
import com.winlator.cmod.shared.android.DirectoryPickerDialog
import com.winlator.cmod.shared.ui.dialog.WinNativeComposeDialogs
import com.winlator.cmod.shared.ui.nav.paneNavItem
import java.io.File
import kotlinx.coroutines.launch

/**
 * The "Nintendo Switch" settings sub-screen, extracted from RetroDefaultsScreen so that
 * file stays small. Rendered as a full-screen layer (NOT inside the outer LazyColumn) so
 * its own scrolling list resolves fillMaxSize against the viewport. Owns its refresh
 * state, the Switch key/firmware importers, GPU driver picker, locale settings, bulk
 * save export/import, and controller settings.
 *
 * @param onBack returns to the landing MENU.
 */
@Composable
internal fun RetroSwitchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var showControllerSettings by remember { mutableStateOf(false) }
    var showCitronHostRoom by remember { mutableStateOf(false) }
    var showCitronConnectRoom by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

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

    // Load disk-backed data off the main thread and cache it in state. Reading these
    // directly in composition (listFiles/mkdirs) ran main-thread I/O and re-ran every time
    // an item recomposed or scrolled back into view, which caused scroll jank. Reloaded
    // whenever refresh changes (i.e. after an import).
    var switchKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var fwCount by remember { mutableIntStateOf(0) }
    var switchDrivers by remember { mutableStateOf<List<com.armsx2.CustomDriver.InstalledDriver>>(emptyList()) }
    LaunchedEffect(refresh) {
        val data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Triple(
                RetroBiosImport.installedSwitchKeys(context),
                RetroBiosImport.installedSwitchFirmwareCount(context),
                runCatching { com.armsx2.CustomDriver.listInstalled(context) }.getOrDefault(emptyList()),
            )
        }
        switchKeys = data.first
        fwCount = data.second
        switchDrivers = data.third
    }

    @Suppress("UNUSED_EXPRESSION") refresh

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(PageBg)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            stickyHeader {
                RetroBackRow { onBack() }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        RetroSettingGroup {
                            RetroGroupTitle(stringResource(R.string.retro_scr_nintendo_switch_keys))
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
                    }
                    Column(Modifier.weight(1f)) {
                        RetroSettingGroup {
                            RetroGroupTitle(stringResource(R.string.retro_scr_nintendo_switch_firmware))
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
                    }
                }
            }
            item {
                RetroSettingGroup {
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
            }
            item {
                RetroSettingGroup {
                    RetroGroupTitle(stringResource(R.string.retro_scr_emulated_system))
                    val citronPrefs = remember { context.getSharedPreferences("CITRON", android.content.Context.MODE_PRIVATE) }
                    // region_index / language_index values are the emulator's own enum
                    // indices (see emulator res regionValues/languageValues); the lists are
                    // ordered by value so the dropdown index equals the stored setting value.
                    val regionLabels = listOf(
                        stringResource(R.string.retro_scr_region_japan),
                        stringResource(R.string.retro_scr_region_usa),
                        stringResource(R.string.retro_scr_region_europe),
                        stringResource(R.string.retro_scr_region_australia),
                        stringResource(R.string.retro_scr_region_china),
                        stringResource(R.string.retro_scr_region_korea),
                        stringResource(R.string.retro_scr_region_taiwan),
                    )
                    val languageLabels = listOf(
                        stringResource(R.string.retro_scr_lang_japanese),
                        stringResource(R.string.retro_scr_lang_english),
                        stringResource(R.string.retro_scr_lang_french),
                        stringResource(R.string.retro_scr_lang_german),
                        stringResource(R.string.retro_scr_lang_italian),
                        stringResource(R.string.retro_scr_lang_spanish),
                        stringResource(R.string.retro_scr_lang_chinese),
                        stringResource(R.string.retro_scr_lang_korean),
                        stringResource(R.string.retro_scr_lang_dutch),
                        stringResource(R.string.retro_scr_lang_portuguese),
                        stringResource(R.string.retro_scr_lang_russian),
                        stringResource(R.string.retro_scr_lang_taiwanese),
                        stringResource(R.string.retro_scr_lang_british_english),
                        stringResource(R.string.retro_scr_lang_canadian_french),
                        stringResource(R.string.retro_scr_lang_latin_american_spanish),
                        stringResource(R.string.retro_scr_lang_simplified_chinese),
                        stringResource(R.string.retro_scr_lang_traditional_chinese),
                        stringResource(R.string.retro_scr_lang_brazilian_portuguese),
                    )
                    // Hold the selection in Compose state so the choice is reflected
                    // immediately after picking. Relying on refresh++ + re-reading
                    // SharedPreferences during composition didn't reliably update the
                    // visible selection until the screen was re-entered. We still write
                    // the pref so the value survives re-entry and is read at game launch.
                    var curRegion by remember { mutableIntStateOf(citronPrefs.getInt("wn.switch.region_index", SwitchLocaleDefaults.defaultRegion()).coerceIn(regionLabels.indices)) }
                    var curLanguage by remember { mutableIntStateOf(citronPrefs.getInt("wn.switch.language_index", SwitchLocaleDefaults.defaultLanguage()).coerceIn(languageLabels.indices)) }
                    RetroSettingDropdown(stringResource(R.string.retro_scr_emulated_region), regionLabels, curRegion) { idx ->
                        curRegion = idx
                        citronPrefs.edit().putInt("wn.switch.region_index", idx).apply()
                    }
                    RetroSettingDropdown(stringResource(R.string.retro_scr_emulated_language), languageLabels, curLanguage) { idx ->
                        curLanguage = idx
                        citronPrefs.edit().putInt("wn.switch.language_index", idx).apply()
                    }
                }
            }
            item {
                RetroSettingGroup {
                    RetroGroupTitle(stringResource(R.string.retro_scr_switch_saves))
                    RetroInfoRow(
                        stringResource(R.string.retro_scr_switch_saves_hint_title),
                        stringResource(R.string.retro_scr_switch_saves_hint),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val loading = WinNativeComposeDialogs.showLoading(
                                        context,
                                        context.getString(R.string.retro_scr_exporting_switch_saves),
                                    )
                                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        CitronSaveManager.exportAllSaves(context)
                                    }
                                    loading?.dismiss()
                                    if (result != null) {
                                        Toast.makeText(context, context.getString(R.string.retro_scr_switch_saves_exported, result.absolutePath), Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.retro_scr_switch_saves_exported_failed), Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .paneNavItem(
                                        cornerRadius = 8.dp,
                                        onActivate = { /* activated via onClick */ },
                                        highlightColor = Color(0xFF4FC3F7),
                                        tapToSelect = true,
                                    ),
                        ) {
                            Text(stringResource(R.string.retro_scr_export_switch_saves))
                        }
                        Button(
                            onClick = {
                                val activity = context as? android.app.Activity
                                if (activity != null) {
                                    DirectoryPickerDialog.showFile(
                                        activity = activity,
                                        title = context.getString(R.string.retro_scr_import_switch_saves),
                                        allowedExtensions = setOf("zip"),
                                    ) { path ->
                                        scope.launch {
                                            val loading = WinNativeComposeDialogs.showLoading(
                                                context,
                                                context.getString(R.string.retro_scr_importing_switch_saves),
                                            )
                                            val count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                CitronSaveManager.importAllSaves(context, File(path))
                                            }
                                            loading?.dismiss()
                                            if (count > 0) {
                                                Toast.makeText(context, context.getString(R.string.retro_scr_switch_saves_imported, count), Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, context.getString(R.string.retro_scr_switch_saves_imported_failed), Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                            },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .paneNavItem(
                                        cornerRadius = 8.dp,
                                        onActivate = { /* activated via onClick */ },
                                        highlightColor = Color(0xFF4FC3F7),
                                        tapToSelect = true,
                                    ),
                        ) {
                            Text(stringResource(R.string.retro_scr_import_switch_saves))
                        }
                    }
                }
            }
            item {
                RetroSettingGroup {
                    RetroGroupTitle(stringResource(R.string.retro_scr_multiplayer))
                    RetroInfoRow(
                        stringResource(R.string.retro_scr_multiplayer_status),
                        if (CitronMultiplayerLobby.snapshot.isConnected)
                            stringResource(R.string.retro_scr_multiplayer_connected)
                        else if (CitronMultiplayerLobby.snapshot.isHosting)
                            stringResource(R.string.retro_scr_multiplayer_hosting)
                        else
                            stringResource(R.string.retro_scr_multiplayer_disconnected),
                    )
                    val snap = CitronMultiplayerLobby.snapshot
                    if (snap.roomName != null) {
                        RetroInfoRow(
                            stringResource(R.string.retro_scr_multiplayer_room_name),
                            snap.roomName,
                        )
                    }
                    if (snap.roomIp != null) {
                        RetroInfoRow(
                            "IP",
                            snap.roomIp,
                        )
                    }
                    val inRoom = snap.isConnected || snap.isHosting
                    if (!inRoom) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { showCitronHostRoom = true },
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .paneNavItem(
                                            cornerRadius = 8.dp,
                                            onActivate = { showCitronHostRoom = true },
                                            highlightColor = Color(0xFF4FC3F7),
                                            tapToSelect = true,
                                        ),
                            ) {
                                Text(stringResource(R.string.retro_scr_multiplayer_create_room))
                            }
                            Button(
                                onClick = { showCitronConnectRoom = true },
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .paneNavItem(
                                            cornerRadius = 8.dp,
                                            onActivate = { showCitronConnectRoom = true },
                                            highlightColor = Color(0xFF4FC3F7),
                                            tapToSelect = true,
                                        ),
                            ) {
                                Text(stringResource(R.string.retro_scr_multiplayer_join_room))
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                scope.launch {
                                    CitronMultiplayerLobby.leaveOrClose(snap.isHosting)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .paneNavItem(
                                    cornerRadius = 8.dp,
                                    onActivate = { },
                                    highlightColor = Color(0xFFFF5252),
                                    tapToSelect = true,
                                ),
                        ) {
                            Text(stringResource(R.string.retro_scr_multiplayer_leave))
                        }
                    }
                }
            }
            item {
                RetroSettingGroup {
                    RetroGroupTitle(stringResource(R.string.retro_scr_external_controller))
                    Button(
                        onClick = { showControllerSettings = true },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .paneNavItem(
                                    cornerRadius = 8.dp,
                                    onActivate = { showControllerSettings = true },
                                    highlightColor = Color(0xFF4FC3F7),
                                    tapToSelect = true,
                                ),
                    ) {
                        Text(stringResource(R.string.retro_scr_controller_settings))
                    }
                }
            }
        }
        if (showControllerSettings) {
            CitronControllerSettingsScreen(onBack = { showControllerSettings = false })
        }
        if (showCitronHostRoom) {
            CitronHostRoomSheet(onBack = { showCitronHostRoom = false })
        }
        if (showCitronConnectRoom) {
            CitronDirectConnectSheet(onBack = { showCitronConnectRoom = false })
        }
    }
}