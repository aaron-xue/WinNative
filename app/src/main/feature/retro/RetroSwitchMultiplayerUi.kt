package com.winlator.cmod.feature.retro

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.shared.theme.GameSettingsStyle
import com.winlator.cmod.shared.ui.nav.paneNavItem
import kotlinx.coroutines.launch
import org.citron.citron_emu.wn.CitronBootstrap

@Composable
internal fun CitronHostRoomSheet(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val prefs = remember {
        context.getSharedPreferences("CITRON", android.content.Context.MODE_PRIVATE)
    }
    var roomName by remember {
        mutableStateOf(prefs.getString("mp_host_room_name", "") ?: "")
    }
    var nickname by remember {
        mutableStateOf(prefs.getString("mp_host_nickname", "") ?: "")
    }
    var port by remember {
        mutableStateOf(
            prefs.getString(
                "mp_host_port",
                MultiplayerValidation.DEFAULT_PORT.toString()
            ) ?: MultiplayerValidation.DEFAULT_PORT.toString()
        )
    }
    var maxPlayers by remember {
        mutableIntStateOf(
            try {
                prefs.getInt("mp_host_max_players", 4)
            } catch (_: ClassCastException) {
                prefs.getString("mp_host_max_players", "4")?.toIntOrNull() ?: 4
            }
        )
    }
    var password by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    var roomNameError by remember { mutableStateOf<String?>(null) }
    var nicknameError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        CitronBootstrap.ensure(context)
        CitronMultiplayerLobby.startPolling()
        onDispose { CitronMultiplayerLobby.stopPolling() }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(PageBg)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            stickyHeader { RetroBackRow { onBack() } }
            item {
                RetroSettingGroup {
                    RetroGroupTitle(stringResource(R.string.retro_scr_multiplayer_create_room))
                    MultiplayerTextField(
                        label = stringResource(R.string.retro_scr_multiplayer_room_name) + " *",
                        value = roomName,
                        placeholder = stringResource(R.string.retro_scr_multiplayer_room_name_placeholder),
                        error = roomNameError,
                        onChange = { roomName = it; roomNameError = null },
                    )
                    MultiplayerTextField(
                        label = stringResource(R.string.retro_scr_multiplayer_nickname) + " *",
                        value = nickname,
                        placeholder = stringResource(R.string.retro_scr_multiplayer_nickname_placeholder),
                        error = nicknameError,
                        onChange = { nickname = it; nicknameError = null },
                    )
                    MultiplayerTextField(
                        label = stringResource(R.string.retro_scr_multiplayer_port),
                        value = port,
                        placeholder = MultiplayerValidation.DEFAULT_PORT.toString(),
                        numeric = true,
                        error = portError,
                        onChange = { port = it; portError = null },
                    )
                    MultiplayerSlider(
                        label = stringResource(R.string.retro_scr_multiplayer_max_players),
                        value = maxPlayers,
                        valueRange = 1..16,
                        onChange = { maxPlayers = it },
                    )
                    MultiplayerTextField(
                        label = stringResource(R.string.retro_scr_multiplayer_password),
                        value = password,
                        placeholder = stringResource(R.string.retro_scr_multiplayer_password_placeholder),
                        onChange = { password = it },
                    )
                    statusMsg?.let { msg ->
                        Text(msg, color = PageSub, modifier = Modifier.padding(top = 4.dp))
                    }
                    Button(
                        onClick = {
                            if (creating) return@Button
                            tried = true
                            var valid = true
                            if (roomName.isBlank()) {
                                roomNameError = context.getString(R.string.retro_scr_multiplayer_room_name_required)
                                valid = false
                            }
                            if (nickname.isBlank()) {
                                nicknameError = context.getString(R.string.retro_scr_multiplayer_nickname_required)
                                valid = false
                            }
                            val portNum = port.toIntOrNull()
                            if (portNum == null || !MultiplayerValidation.isValidPort(portNum)) {
                                portError = context.getString(R.string.retro_scr_multiplayer_invalid_port)
                                valid = false
                            }
                            if (!valid) return@Button

                            val name = roomName.trim()
                            val nick = nickname.trim()
                            creating = true
                            statusMsg = context.getString(R.string.retro_scr_multiplayer_creating)
                            scope.launch {
                                prefs.edit()
                                    .putString("mp_host_room_name", roomName)
                                    .putString("mp_host_nickname", nickname)
                                    .putString("mp_host_port", port)
                                    .putInt("mp_host_max_players", maxPlayers)
                                    .apply()
                                val result = CitronMultiplayerLobby.host(
                                    HostRoomParams(name, nick, "", portNum!!, password, maxPlayers)
                                )
                                creating = false
                                if (result.started) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.retro_scr_multiplayer_host_success),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onBack()
                                } else {
                                    statusMsg = errorMsg(context, result.error)
                                }
                            }
                        },
                        enabled = !creating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .paneNavItem(
                                cornerRadius = 8.dp,
                                onActivate = { },
                                highlightColor = Color(0xFF4FC3F7),
                                tapToSelect = true,
                            ),
                    ) {
                        Text(
                            if (creating) stringResource(R.string.retro_scr_multiplayer_creating)
                            else stringResource(R.string.retro_scr_multiplayer_create)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CitronDirectConnectSheet(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val prefs = remember {
        context.getSharedPreferences("CITRON", android.content.Context.MODE_PRIVATE)
    }
    var host by remember {
        mutableStateOf(prefs.getString("mp_connect_host", "") ?: "")
    }
    var port by remember {
        mutableStateOf(
            prefs.getString(
                "mp_connect_port",
                MultiplayerValidation.DEFAULT_PORT.toString()
            ) ?: MultiplayerValidation.DEFAULT_PORT.toString()
        )
    }
    var nickname by remember {
        mutableStateOf(prefs.getString("mp_connect_nickname", "") ?: "")
    }
    var password by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    var hostError by remember { mutableStateOf<String?>(null) }
    var nicknameError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        CitronBootstrap.ensure(context)
        CitronMultiplayerLobby.startPolling()
        onDispose { CitronMultiplayerLobby.stopPolling() }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(PageBg)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            stickyHeader { RetroBackRow { onBack() } }
            item {
                RetroSettingGroup {
                    RetroGroupTitle(stringResource(R.string.retro_scr_multiplayer_join_room))
                    MultiplayerTextField(
                        label = stringResource(R.string.retro_scr_multiplayer_host_address) + " *",
                        value = host,
                        placeholder = "192.168.",
                        error = hostError,
                        onChange = { host = it; hostError = null },
                    )
                    MultiplayerTextField(
                        label = stringResource(R.string.retro_scr_multiplayer_port),
                        value = port,
                        placeholder = MultiplayerValidation.DEFAULT_PORT.toString(),
                        numeric = true,
                        error = portError,
                        onChange = { port = it; portError = null },
                    )
                    MultiplayerTextField(
                        label = stringResource(R.string.retro_scr_multiplayer_nickname) + " *",
                        value = nickname,
                        placeholder = stringResource(R.string.retro_scr_multiplayer_nickname_placeholder),
                        error = nicknameError,
                        onChange = { nickname = it; nicknameError = null },
                    )
                    MultiplayerTextField(
                        label = stringResource(R.string.retro_scr_multiplayer_password),
                        value = password,
                        placeholder = stringResource(R.string.retro_scr_multiplayer_password_placeholder),
                        onChange = { password = it },
                    )
                    statusMsg?.let { msg ->
                        Text(msg, color = PageSub, modifier = Modifier.padding(top = 4.dp))
                    }
                    Button(
                        onClick = {
                            if (joining) return@Button
                            tried = true
                            var valid = true
                            if (host.isBlank()) {
                                hostError = context.getString(R.string.retro_scr_multiplayer_host_required)
                                valid = false
                            }
                            val portNum = port.toIntOrNull()
                            if (portNum == null || !MultiplayerValidation.isValidPort(portNum)) {
                                portError = context.getString(R.string.retro_scr_multiplayer_invalid_port)
                                valid = false
                            }
                            if (nickname.isBlank()) {
                                nicknameError = context.getString(R.string.retro_scr_multiplayer_nickname_required)
                                valid = false
                            }
                            if (!valid) return@Button

                            val nick = nickname.trim()
                            joining = true
                            statusMsg = context.getString(R.string.retro_scr_multiplayer_joining)
                            scope.launch {
                                prefs.edit()
                                    .putString("mp_connect_host", host)
                                    .putString("mp_connect_port", port)
                                    .putString("mp_connect_nickname", nickname)
                                    .apply()
                                val result = CitronMultiplayerLobby.connect(
                                    DirectConnectParams(host.trim(), portNum!!, nick, password)
                                )
                                joining = false
                                if (result.started) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.retro_scr_multiplayer_join_success),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onBack()
                                } else {
                                    statusMsg = errorMsg(context, result.error)
                                }
                            }
                        },
                        enabled = !joining,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .paneNavItem(
                                cornerRadius = 8.dp,
                                onActivate = { },
                                highlightColor = Color(0xFF4FC3F7),
                                tapToSelect = true,
                            ),
                    ) {
                        Text(
                            if (joining) stringResource(R.string.retro_scr_multiplayer_joining)
                            else stringResource(R.string.retro_scr_multiplayer_join)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiplayerSlider(
    label: String,
    value: Int,
    valueRange: IntRange,
    onChange: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = GameSettingsStyle.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                value.toString(),
                color = GameSettingsStyle.AccentBlue,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(4.dp))
        val stepCount = ((valueRange.last - valueRange.first - 1)).coerceAtLeast(0)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = stepCount,
            colors = SliderDefaults.colors(
                thumbColor = GameSettingsStyle.AccentBlue,
                activeTrackColor = GameSettingsStyle.AccentBlue,
                inactiveTrackColor = GameSettingsStyle.CardBorder.copy(alpha = 0.5f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().height(26.dp),
        )
    }
}

@Composable
private fun MultiplayerTextField(
    label: String,
    value: String,
    placeholder: String,
    numeric: Boolean = false,
    error: String? = null,
    onChange: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            label,
            color = GameSettingsStyle.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            isError = error != null,
            placeholder = { Text(placeholder, fontSize = 12.sp) },
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = GameSettingsStyle.AccentBlue,
                unfocusedBorderColor = if (error != null) GameSettingsStyle.DangerRed else GameSettingsStyle.CardBorder,
                focusedTextColor = GameSettingsStyle.TextPrimary,
                unfocusedTextColor = GameSettingsStyle.TextPrimary,
                errorBorderColor = GameSettingsStyle.DangerRed,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Text(
                error,
                color = GameSettingsStyle.DangerRed,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun errorMsg(
    context: android.content.Context,
    error: MultiplayerError?
): String {
    return context.getString(
        when (error) {
            MultiplayerError.COULD_NOT_CONNECT -> R.string.retro_scr_multiplayer_error_connect
            MultiplayerError.WRONG_PASSWORD -> R.string.retro_scr_multiplayer_error_password
            MultiplayerError.ROOM_FULL -> R.string.retro_scr_multiplayer_error_full
            MultiplayerError.WRONG_VERSION -> R.string.retro_scr_multiplayer_error_version
            MultiplayerError.NAME_COLLISION -> R.string.retro_scr_multiplayer_error_name
            MultiplayerError.IP_COLLISION -> R.string.retro_scr_multiplayer_error_ip
            MultiplayerError.NO_NETWORK_INTERFACE -> R.string.retro_scr_multiplayer_error_network
            MultiplayerError.COULD_NOT_CREATE_ROOM -> R.string.retro_scr_multiplayer_error_create
            MultiplayerError.MEMBER_BUSY -> R.string.retro_scr_multiplayer_error_busy
            else -> R.string.retro_scr_multiplayer_error_unknown
        }
    )
}