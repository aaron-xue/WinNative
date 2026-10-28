@file:Suppress("unused")

package com.winlator.cmod.feature.retro

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.citron.citron_emu.NativeLibrary
import org.citron.citron_emu.utils.NativeConfig

enum class RoomConnectionState(val nativeValue: Int) {
    UNINITIALIZED(0),
    IDLE(1),
    JOINING(2),
    JOINED(3),
    MODERATOR(4),
    UNKNOWN(-1);

    val isConnected: Boolean
        get() = this == JOINED || this == MODERATOR

    companion object {
        fun fromNative(value: Int): RoomConnectionState =
            entries.firstOrNull { it.nativeValue == value } ?: UNKNOWN
    }
}

enum class MultiplayerError {
    LOST_CONNECTION,
    KICKED,
    NAME_COLLISION,
    IP_COLLISION,
    WRONG_VERSION,
    WRONG_PASSWORD,
    COULD_NOT_CONNECT,
    ROOM_FULL,
    BANNED,
    PERMISSION_DENIED,
    NO_SUCH_USER,
    NETWORK_NOT_INITIALIZED,
    INVALID_ARGUMENTS,
    NO_NETWORK_INTERFACE,
    ROOM_UNAVAILABLE,
    ROOM_ALREADY_OPEN,
    MEMBER_BUSY,
    COULD_NOT_CREATE_ROOM,
    LOCAL_JOIN_FAILED,
    UNKNOWN;

    companion object {
        private val nativeValues by lazy(NativeLibrary::getMultiplayerErrorValues)

        fun fromNative(value: Int): MultiplayerError? {
            if (value < 0) return null
            return entries.getOrNull(nativeValues.indexOf(value)) ?: UNKNOWN
        }
    }
}

data class MultiplayerSnapshot(
    val connectionState: RoomConnectionState = RoomConnectionState.UNINITIALIZED,
    val isHosting: Boolean = false,
    val isEmulationRunning: Boolean = false,
    val lastError: MultiplayerError? = null,
    val roomName: String? = null,
    val roomIp: String? = null,
) {
    val isConnected: Boolean
        get() = connectionState.isConnected

    val canStartConnection: Boolean
        get() = connectionState != RoomConnectionState.JOINING && !isConnected && !isHosting
}

data class DirectConnectParams(
    val host: String,
    val port: Int,
    val nickname: String,
    val password: String
)

data class HostRoomParams(
    val roomName: String,
    val nickname: String,
    val description: String,
    val port: Int,
    val password: String,
    val maxPlayers: Int
)

data class StartRoomResult(
    val started: Boolean,
    val error: MultiplayerError? = null,
    val airplaneModeDisabled: Boolean = false,
    val snapshot: MultiplayerSnapshot? = null
)

object MultiplayerValidation {
    const val DEFAULT_PORT = 24872
    const val MIN_IDENTIFIER_LENGTH = 4
    const val MAX_IDENTIFIER_LENGTH = 20
    const val MAX_HOST_LENGTH = 253
    const val MAX_PORT = 65535
    const val MIN_PLAYERS = 2
    const val MAX_PLAYERS = 16
    val maxChatMessageBytes by lazy(NativeLibrary::getMaxRoomChatMessageBytes)

    val identifierPattern = Regex("[a-zA-Z0-9._ -]+")

    fun isValidIdentifier(value: String): Boolean =
        value.length in MIN_IDENTIFIER_LENGTH..MAX_IDENTIFIER_LENGTH &&
            value.matches(identifierPattern)

    fun isValidHost(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_HOST_LENGTH

    fun isValidPort(value: Int?): Boolean =
        value != null && value in 1..MAX_PORT

    fun isValidPlayerCount(value: Int?): Boolean =
        value != null && value in MIN_PLAYERS..MAX_PLAYERS
}

object CitronMultiplayerLobby {
    private val main = Handler(Looper.getMainLooper())
    private var polling = false

    var snapshot by mutableStateOf(MultiplayerSnapshot())
        private set

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            refreshSnapshot()
            main.postDelayed(this, 1000L)
        }
    }

    fun startPolling() {
        if (polling) return
        polling = true
        main.post(pollRunnable)
    }

    fun stopPolling() {
        polling = false
        main.removeCallbacks(pollRunnable)
    }

    private fun refreshSnapshot() {
        val state = RoomConnectionState.fromNative(
            NativeLibrary.getRoomConnectionState()
        )
        val error = if (state == RoomConnectionState.IDLE) {
            MultiplayerError.fromNative(NativeLibrary.getRoomLastError())
        } else {
            null
        }
        val (roomName, roomIp) = try {
            val info = NativeLibrary.getRoomInfo()
            val name = info.getOrNull(0)?.takeIf(String::isNotEmpty)
            val ip = info.getOrNull(1)?.takeIf(String::isNotEmpty)
            Pair(name ?: snapshot.roomName, ip ?: snapshot.roomIp)
        } catch (_: Exception) {
            Pair(snapshot.roomName, snapshot.roomIp)
        }
        snapshot = MultiplayerSnapshot(
            connectionState = state,
            isHosting = NativeLibrary.isHostingRoom(),
            isEmulationRunning = NativeLibrary.isRunning(),
            lastError = error,
            roomName = roomName,
            roomIp = roomIp,
        )
    }

    suspend fun connect(params: DirectConnectParams): StartRoomResult =
        withContext(Dispatchers.IO) {
            val current = readSnapshot()
            if (!current.canStartConnection) {
                return@withContext StartRoomResult(false, MultiplayerError.MEMBER_BUSY)
            }
            val airplaneDisabled = disableAirplaneMode()
            val started = NativeLibrary.connectToRoom(
                params.nickname, params.host, params.port, params.password
            )
            val newSnap = readSnapshot()
            val error = if (started) null else {
                var e: MultiplayerError? = null
                repeat(10) {
                    MultiplayerError.fromNative(
                        NativeLibrary.getRoomLastError()
                    )?.let { e = it }
                }
                e ?: MultiplayerError.UNKNOWN
            }
            snapshot = newSnap.copy(
                roomName = newSnap.roomName ?: params.host,
                roomIp = newSnap.roomIp ?: params.host,
            )
            StartRoomResult(started, error, airplaneDisabled, snapshot)
        }

    suspend fun host(params: HostRoomParams): StartRoomResult =
        withContext(Dispatchers.IO) {
            val current = readSnapshot()
            if (!current.canStartConnection) {
                return@withContext StartRoomResult(false, MultiplayerError.MEMBER_BUSY)
            }
            val airplaneDisabled = disableAirplaneMode()
            val started = NativeLibrary.hostRoom(
                params.nickname, params.roomName, params.description,
                params.port, params.password, params.maxPlayers
            )
            val newSnap = readSnapshot()
            val error = if (started) null else {
                var e: MultiplayerError? = null
                repeat(10) {
                    MultiplayerError.fromNative(
                        NativeLibrary.getRoomLastError()
                    )?.let { e = it }
                }
                e ?: MultiplayerError.UNKNOWN
            }
            snapshot = newSnap.copy(
                roomName = newSnap.roomName ?: params.roomName,
                roomIp = newSnap.roomIp ?: getLocalIpAddress(),
            )
            StartRoomResult(started, error, airplaneDisabled, snapshot)
        }

    suspend fun leaveOrClose(hosting: Boolean) = withContext(Dispatchers.IO) {
        if (hosting) NativeLibrary.closeRoom() else NativeLibrary.leaveRoom()
        snapshot = readSnapshot()
    }

    private fun readSnapshot(): MultiplayerSnapshot {
        val state = RoomConnectionState.fromNative(
            NativeLibrary.getRoomConnectionState()
        )
        return MultiplayerSnapshot(
            connectionState = state,
            isHosting = NativeLibrary.isHostingRoom(),
            isEmulationRunning = NativeLibrary.isRunning(),
            lastError = null
        )
    }

    private fun disableAirplaneMode(): Boolean {
        val ctx = NativeLibrary.appContext ?: return false
        val prefs = ctx.getSharedPreferences("CITRON", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("airplane_mode", false)) return false
        prefs.edit().putBoolean("airplane_mode", false).apply()
        NativeConfig.setBoolean("airplane_mode", false)
        NativeConfig.saveGlobalConfig()
        return true
    }

    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { !it.isLoopback && it.isUp }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull()
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}