package com.winlator.cmod.feature.settings.containers;

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.winlator.cmod.R
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerCreation
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.MmkvPreferences
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.wine.WineInfo
import com.winlator.cmod.runtime.wine.WineRegistryEditor
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ─── Palette matching ContainerFileManagerActivity ─────────────────────
private val RegBg = Color(0xFF18181D)
private val RegCard = Color(0xFF1C1C2A)
private val RegSubcard = Color(0xFF161622)
private val RegOutline = Color(0xFF2A2A3A)
private val RegIconBox = Color(0xFF242434)
private val RegAccent = Color(0xFF1A9FFF)
private val RegTextPrimary = Color(0xFFF0F4FF)
private val RegTextSecondary = Color(0xFF7A8FA8)
private val RegDanger = Color(0xFFFF7A88)

private val REG_VALUE_TYPES = listOf("String", "Dword", "Qword", "Hex", "ExpandString", "MultiString")

// Модель хайва — как в CronyX: Computer-экран со списком из 5 корневых разделов.
// Каждый хайв сопоставляется с .reg-файлом Wine и корневым путём внутри файла.
private data class RegistryHive(
    val key: String,
    val fileName: String,
    val rootPrefix: String,
    val description: String,
)

private val ALL_HIVES = listOf(
    RegistryHive(
        key = "HKEY_CLASSES_ROOT",
        fileName = "system.reg",
        rootPrefix = "Software\\Classes",
        description = "File associations and COM classes",
    ),
    RegistryHive(
        key = "HKEY_CURRENT_USER",
        fileName = "user.reg",
        rootPrefix = "",
        description = "Current user profile",
    ),
    RegistryHive(
        key = "HKEY_LOCAL_MACHINE",
        fileName = "system.reg",
        rootPrefix = "",
        description = "Machine-wide settings",
    ),
    RegistryHive(
        key = "HKEY_USERS",
        fileName = "user.reg",
        rootPrefix = "",
        description = "All user profiles",
    ),
    RegistryHive(
        key = "HKEY_CURRENT_CONFIG",
        fileName = "system.reg",
        rootPrefix = "System\\CurrentControlSet\\Hardware Profiles\\Current",
        description = "Current hardware profile",
    ),
)

private const val PREFS_SPOOF_IDENTIFIER = "reg_spoof_identifier"
private const val PREFS_SPOOF_VENDOR = "reg_spoof_vendor"
private const val PREFS_SPOOF_MHZ = "reg_spoof_mhz"
private const val PREFS_SPOOF_FEATURESET = "reg_spoof_featureset"

private data class CpuSpoofPreset(
    val name: String,
    val identifier: String,
    val vendor: String,
    val mhz: Int,
    val featureSet: String,
)

private val PRESET_ARM = CpuSpoofPreset(
    name = "ARM",
    identifier = "ARMv8 (64-bit) Family 8 Model D82 Revision 1",
    vendor = "ARM",
    mhz = 2016,
    featureSet = "f4",
)

private val PRESET_INTEL = CpuSpoofPreset(
    name = "Intel x86",
    identifier = "Intel64 Family 6 Model 70 Stepping 1",
    vendor = "GenuineIntel",
    mhz = 2016,
    featureSet = "2b7bbfff",
)

class ContainerRegistryEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immersive display — same handling as ContainerFileManagerActivity
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val containerId = intent.getIntExtra("container_id", -1)
        if (containerId == -1) {
            finish()
            return
        }
        val container = ContainerManager(this).getContainerById(containerId) ?: run {
            finish()
            return
        }

        val contentsManager = ContentsManager(this)
        val wineVersionId = container.getWineVersion()
        val wineVersion =
            ContainerCreation.displayNameForWineVersion(this, contentsManager, wineVersionId)
        val wineInfo = WineInfo.fromIdentifier(this, contentsManager, wineVersionId)
        val wineArch = if (wineInfo.isArm64EC()) "arm64ec" else "x86_64"
        val rootDir = container.getRootDir()

        setContent {
            WinNativeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = RegBg,
                ) {
                    RegistryEditorScreen(
                        containerRootDir = rootDir,
                        wineVersion = wineVersion,
                        wineArch = wineArch,
                        containerName = container.name,
                        onToast = { msg ->
                            Toast.makeText(
                                this@ContainerRegistryEditorActivity,
                                msg,
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                        onBack = { finish() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RegistryEditorScreen(
    containerRootDir: File?,
    wineVersion: String,
    wineArch: String,
    containerName: String,
    onToast: (String) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val sp = remember { MmkvPreferences() }
    val scope = rememberCoroutineScope()

    // Computer-корневой экран: null = показать список из 5 хайвов
    var selectedHive by remember { mutableStateOf<RegistryHive?>(null) }
    var currentPath by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }

    // Поиск по текущему хайву
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<String>?>(null) }
    var searchExpanded by remember { mutableStateOf(false) }
    var showValuesDialog by remember { mutableStateOf(false) }

    val regFile = remember(containerRootDir, selectedHive) {
        selectedHive?.let { File(containerRootDir, ".wine/${it.fileName}") }
    }
    val hivePrefix = selectedHive?.key ?: ""
    val rootPrefix = selectedHive?.rootPrefix ?: ""

    // Путь внутри .reg-файла для заданного пути, отображаемого в UI (относительно хайва)
    fun filePathFor(uiPath: String): String = when {
        rootPrefix.isEmpty() -> uiPath
        uiPath.isEmpty() -> rootPrefix
        else -> "$rootPrefix\\$uiPath"
    }

    var values by remember { mutableStateOf<List<WineRegistryEditor.RegValue>>(emptyList()) }
    var fileExists by remember { mutableStateOf(false) }

    // Дерево: загруженные подключи по пути + раскрытые узлы
    var loadedChildren by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var expandedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    val treeListState = rememberLazyListState()
    var pendingScrollPath by remember { mutableStateOf<String?>(null) }

    fun loadChildren(path: String) {
        if (loadedChildren.containsKey(path)) return
        scope.launch(Dispatchers.IO) {
            val children =
                regFile?.let { WineRegistryEditor(it).use { r -> r.getSubKeys(filePathFor(path)) } }
                    ?: emptyList()
            loadedChildren = loadedChildren + (path to children)
        }
    }

    fun toggleNode(path: String) {
        if (path in expandedPaths) {
            expandedPaths = expandedPaths - path
        } else {
            expandedPaths = expandedPaths + path
            loadChildren(path)
        }
    }

    // Раскрывает всех предков пути и загружает их подключи, чтобы узел стал видимым в дереве
    suspend fun expandPathTo(path: String) {
        val parts = path.split("\\").filter { it.isNotEmpty() }
        val ancestorPaths = buildList {
            var acc = ""
            parts.forEach { p ->
                acc = if (acc.isEmpty()) p else "$acc\\$p"
                add(acc)
            }
        }
        val missing = ancestorPaths.filter { !loadedChildren.containsKey(it) }
        val fetched = if (missing.isEmpty()) emptyMap()
        else withContext(Dispatchers.IO) {
            missing.associateWith { p ->
                regFile?.let { WineRegistryEditor(it).use { r -> r.getSubKeys(filePathFor(p)) } } ?: emptyList()
            }
        }
        loadedChildren = loadedChildren + fetched
        expandedPaths = expandedPaths + ancestorPaths
    }

    // Принудительно перечитывает подключи узла (сохраняя остальной кеш и развёрнутое состояние)
    fun reloadChildrenForce(path: String) {
        scope.launch(Dispatchers.IO) {
            val children = regFile?.let { WineRegistryEditor(it).use { r -> r.getSubKeys(filePathFor(path)) } } ?: emptyList()
            loadedChildren = loadedChildren + (path to children)
        }
    }

    // После импорта перечитываем корень и все раскрытые узлы, сохраняя развёрнутое состояние
    fun reloadExpandedHive() {
        val paths = expandedPaths + ""
        scope.launch(Dispatchers.IO) {
            val fresh = paths.associateWith { p ->
                regFile?.let { WineRegistryEditor(it).use { r -> r.getSubKeys(filePathFor(p)) } } ?: emptyList()
            }
            loadedChildren = loadedChildren + fresh
        }
    }

    val visibleTreeNodes = remember(loadedChildren, expandedPaths) {
        val result = mutableListOf<Pair<String, Int>>()
        fun walk(path: String, depth: Int) {
            val children = loadedChildren[path] ?: return
            for (child in children) {
                val childPath = if (path.isEmpty()) child else "$path\\$child"
                result.add(childPath to depth)
                if (childPath in expandedPaths) walk(childPath, depth + 1)
            }
        }
        walk("", 0)
        result
    }

    fun selectHive(newHive: RegistryHive) {
        if (newHive == selectedHive) return
        selectedHive = newHive
        currentPath = ""
        loadedChildren = emptyMap()
        expandedPaths = emptySet()
        values = emptyList()
        searchResults = null
        searchQuery = ""
        searchExpanded = false
        showValuesDialog = false
        refreshKey++
    }

    fun backToComputer() {
        selectedHive = null
        currentPath = ""
        loadedChildren = emptyMap()
        expandedPaths = emptySet()
        values = emptyList()
        searchResults = null
        searchQuery = ""
        searchExpanded = false
        showValuesDialog = false
        refreshKey++
    }

    LaunchedEffect(regFile, currentPath, refreshKey) {
        if (regFile == null || !regFile.isFile) {
            values = emptyList()
            fileExists = false
            return@LaunchedEffect
        }
        fileExists = true
        values = WineRegistryEditor(regFile).use { reg -> reg.getValues(filePathFor(currentPath)) }
    }

    // Загрузка корневых подключей выбранного хайва (при первом показе и при переключении хайва).
    // Внимание: не зависит от refreshKey, иначе любое обновление сбрасывало бы весь кеш дерева,
    // и развёрнутые узлы «сворачивались», оставаясь с раскрытой стрелкой.
    LaunchedEffect(regFile, selectedHive) {
        if (regFile == null || !regFile.isFile) return@LaunchedEffect
        loadedChildren = withContext(Dispatchers.IO) {
            WineRegistryEditor(regFile).use { reg ->
                mapOf("" to reg.getSubKeys(filePathFor("")))
            }
        }
    }

    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun copyToClipboard(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("registry", text))
        onToast(ctx.getString(R.string.registry_copied, text))
    }

    fun runSearch() {
        val q = searchQuery.trim()
        if (q.isEmpty() || regFile == null) return
        scope.launch(Dispatchers.IO) {
            val raw = WineRegistryEditor(regFile).use { it.searchKeys(q, 300) }
            // Для хайвов с корневым префиксом убираем его, чтобы пути совпадали с UI
            searchResults = raw.map { p ->
                when {
                    rootPrefix.isEmpty() -> p
                    p == rootPrefix -> ""
                    p.startsWith("$rootPrefix\\") -> p.removePrefix("$rootPrefix\\")
                    else -> p
                }
            }
        }
    }

    var showAddKeyDialog by remember { mutableStateOf(false) }
    var editingValue by remember { mutableStateOf<WineRegistryEditor.RegValue?>(null) }
    var addingValue by remember { mutableStateOf(false) }
    var deleteKeyConfirm by remember { mutableStateOf<String?>(null) }
    var deleteValueConfirm by remember { mutableStateOf<WineRegistryEditor.RegValue?>(null) }
    var showSpoofDialog by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                onToast(ctx.getString(R.string.registry_import_failed))
                return@rememberLauncherForActivityResult
            }
            val content = decodeRegText(bytes)
            val file = regFile
            if (file != null) {
                // 树形界面：导入到当前 hive
                scope.launch(Dispatchers.IO) {
                    WineRegistryEditor(file).use { it.importRegFile(content) }
                    refreshKey++
                    reloadExpandedHive()
                }
            } else {
                // hive 根界面：按 HKEY_* 前缀分发到各 hive 文件
                scope.launch(Dispatchers.IO) {
                    importRegToHives(containerRootDir, content)
                    refreshKey++
                }
            }
            onToast(ctx.getString(R.string.registry_import_done))
        } catch (e: Exception) {
            onToast(ctx.getString(R.string.registry_import_failed) + ": ${e.message}")
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri == null || regFile == null) return@rememberLauncherForActivityResult
        val path = filePathFor(currentPath)
        val prefix = hivePrefix
        scope.launch(Dispatchers.IO) {
            try {
                val text = WineRegistryEditor(regFile).use { it.exportReg(path, prefix) }
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                }
                onToast(ctx.getString(R.string.registry_export_done))
            } catch (e: Exception) {
                onToast(ctx.getString(R.string.registry_export_failed) + ": ${e.message}")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RegBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 16.dp,
                    top = statusBarPadding.calculateTopPadding(),
                    end = 16.dp,
                    bottom = navBarPadding.calculateBottomPadding()
                        + if (containerRootDir != null && (selectedHive == null || fileExists)) 56.dp else 0.dp,
                ),
        ) {
            // ── Header ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RegEditorIconButton(
                    image = if (selectedHive == null) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Home,
                    tint = RegAccent,
                    onClick = { if (selectedHive == null) onBack() else backToComputer() },
                )
                Spacer(Modifier.width(12.dp))
                if (selectedHive != null && searchExpanded) {
                    // ── 搜索框 (展开于导航栏内) ──
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(RegCard)
                            .border(1.dp, RegAccent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                                tint = RegAccent,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.weight(1f),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = RegTextPrimary,
                                    fontSize = 14.sp,
                                ),
                                cursorBrush = SolidColor(RegAccent),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.registry_search),
                                            color = RegTextSecondary,
                                            fontSize = 14.sp,
                                        )
                                    }
                                    innerTextField()
                                },
                            )
                            if (searchQuery.isNotEmpty()) {
                                RegEditorSmallIconButton(
                                    image = Icons.Outlined.Clear,
                                    tint = RegTextSecondary,
                                    onClick = { searchQuery = "" },
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            RegEditorIconButton(
                                image = Icons.Outlined.Search,
                                tint = RegAccent,
                                onClick = { runSearch() },
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    RegEditorIconButton(
                        image = Icons.Outlined.Close,
                        tint = RegTextSecondary,
                        onClick = { searchExpanded = false; searchResults = null; searchQuery = "" },
                    )
                } else {
                    if (selectedHive == null) {
                        // ── 标题 (Computer 根界面) ──
                        Text(
                            text = stringResource(R.string.registry_editor),
                            color = RegTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        // ── 面包屑 (显示在顶部导航栏 hive 名称位置) ──
                        val pathParts = if (currentPath.isEmpty()) emptyList() else currentPath.split("\\")
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                hivePrefix,
                                color = RegAccent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { backToComputer() },
                                    )
                                    .padding(4.dp),
                            )
                            pathParts.forEachIndexed { index, part ->
                                Text(">", color = RegTextSecondary, fontSize = 12.sp)
                                val targetPath = pathParts.subList(0, index + 1).joinToString("\\")
                                val isLast = index == pathParts.size - 1
                                Text(
                                    part,
                                    color = if (isLast) RegTextPrimary else RegAccent,
                                    fontSize = 13.sp,
                                    fontWeight = if (isLast) FontWeight.Bold else null,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = if (isLast) Modifier.padding(4.dp)
                                    else Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { currentPath = targetPath },
                                        )
                                        .padding(4.dp),
                                )
                            }
                        }
                    }
                    if (selectedHive != null) {
                        Spacer(Modifier.width(8.dp))
                        RegEditorIconButton(
                            image = Icons.Outlined.Search,
                            tint = RegAccent,
                            onClick = { searchExpanded = true },
                        )
                    }
                }
            }

            // ── Separator ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(RegOutline),
            )
            Spacer(Modifier.height(8.dp))

            if (containerRootDir == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.registry_created),
                        color = RegTextSecondary,
                        fontSize = 15.sp,
                    )
                }
                return@Column
            }

            if (selectedHive == null) {
                // ── Computer: корневой экран со списком из 5 хайвов ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.registry_computer),
                        color = RegTextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ALL_HIVES.forEach { hiveEntry ->
                        val hiveFile = File(containerRootDir, ".wine/${hiveEntry.fileName}")
                        val exists = hiveFile.isFile
                        HiveCard(
                            hive = hiveEntry,
                            exists = exists,
                            onClick = { selectHive(hiveEntry) },
                        )
                    }
                }
                return@Column
            }

            if (searchResults != null) {
                val results = searchResults!!
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.registry_search_results, results.size),
                            color = RegAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        RegEditorSmallIconButton(
                            image = Icons.Outlined.Clear,
                            tint = RegTextSecondary,
                            onClick = { searchResults = null; searchQuery = "" },
                        )
                    }
                    if (results.isEmpty()) {
                        Text(
                            text = stringResource(R.string.registry_search_empty),
                            color = RegTextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(8.dp),
                        )
                    } else {
                        results.forEach { keyPath ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(RegSubcard)
                                    .border(1.dp, RegOutline, RoundedCornerShape(10.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            searchResults = null
                                            searchQuery = ""
                                            currentPath = keyPath
                                            // Раскрываем всех предков и прокручиваем к выбранному узлу
                                            pendingScrollPath = keyPath
                                            scope.launch { expandPathTo(keyPath) }
                                        },
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.Folder,
                                        null,
                                        tint = RegAccent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        keyPath,
                                        color = RegTextPrimary,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!fileExists) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.registry_file_not_found),
                        color = RegTextSecondary,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                return@Column
            }

            // ── Дерево ключей ──
            // Автопрокрутка к целевому узлу после перехода из поиска
            LaunchedEffect(visibleTreeNodes, pendingScrollPath) {
                val target = pendingScrollPath ?: return@LaunchedEffect
                val index = visibleTreeNodes.indexOfFirst { it.first == target }
                if (index >= 0) {
                    treeListState.scrollToItem(index + 1) // +1 пропускает заголовок "Computer"
                    pendingScrollPath = null
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 4.dp),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(RegCard)
                        .border(1.dp, RegOutline, RoundedCornerShape(12.dp))
                        .padding(vertical = 4.dp),
                    state = treeListState,
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.registry_computer),
                            color = RegTextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    if (visibleTreeNodes.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.registry_empty),
                                color = RegTextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    items(visibleTreeNodes, key = { it.first }) { (nodePath, depth) ->
                        val nodeName = nodePath.substringAfterLast("\\")
                        val isExpanded = nodePath in expandedPaths
                        val hasLoaded = loadedChildren.containsKey(nodePath)
                        val loadedEmpty = hasLoaded && loadedChildren[nodePath]!!.isEmpty()
                        val isSelected = nodePath == currentPath
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) RegAccent.copy(alpha = 0.12f) else Color.Transparent)
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        currentPath = nodePath
                                    },
                                    onDoubleClick = {
                                        if (!loadedEmpty) toggleNode(nodePath)
                                    },
                                    onLongClick = { copyToClipboard(nodePath) },
                                )
                                .padding(start = (4 + depth * 14).dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (loadedEmpty) {
                                Spacer(Modifier.size(24.dp))
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { toggleNode(nodePath) },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (isExpanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                                        null,
                                        tint = RegTextSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Icon(
                                if (isExpanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                                null,
                                tint = if (isSelected) RegAccent else RegTextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                nodeName + (if (hasLoaded) " (${loadedChildren[nodePath]!!.size})" else ""),
                                color = if (isSelected) RegTextPrimary else RegTextPrimary,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            if (selectedHive?.key == "HKEY_LOCAL_MACHINE") {
                RegEditorActionButton(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    image = Icons.Outlined.Memory,
                    label = stringResource(R.string.registry_cpu_spoof),
                    tint = RegAccent,
                    onClick = { showSpoofDialog = true },
                )
            }
        }

        // ── Плавающая панель действий внизу экрана ──
        // hive 根界面：固定显示导入；树形界面：显示其余操作按钮（含导出）
        if (containerRootDir != null && selectedHive == null) {
            RegEditorBottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                navBarPadding = navBarPadding,
                onImport = { importLauncher.launch(arrayOf("*/*")) },
            )
        } else if (selectedHive != null && fileExists) {
            RegEditorBottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                navBarPadding = navBarPadding,
                enabled = currentPath.isNotEmpty(),
                onAddKey = { showAddKeyDialog = true },
                onAddValue = { addingValue = true },
                onExport = { exportLauncher.launch("registry_export.reg") },
                onEditValue = { showValuesDialog = true },
                onDeleteKey = { if (currentPath.isNotEmpty()) deleteKeyConfirm = currentPath },
            )
        }
    }

    // ── Диалог значений выбранного ключа ──
    if (showValuesDialog) {
        RegEditorOverlay(onDismiss = { showValuesDialog = false }) {
            PopupDialog(
                title = if (currentPath.isEmpty()) hivePrefix else currentPath,
                message = "${stringResource(R.string.registry_values)} (${values.size})",
                icon = Icons.Outlined.Tune,
                confirmLabel = stringResource(R.string.common_ui_ok),
                onConfirm = { showValuesDialog = false },
                onCancel = { showValuesDialog = false },
                accentColor = RegAccent,
                footer = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (currentPath.isNotEmpty()) {
                            RegEditorTextAction(
                                label = stringResource(R.string.registry_delete_key),
                                textColor = RegDanger,
                                onClick = { deleteKeyConfirm = currentPath },
                            )
                        }
                        RegEditorTextAction(
                            label = stringResource(R.string.registry_add_value),
                            textColor = RegAccent,
                            onClick = { addingValue = true },
                        )
                    }
                },
                content = {
                    if (values.isEmpty()) {
                        Text(
                            text = stringResource(R.string.registry_empty),
                            color = RegTextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(8.dp),
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            values.forEach { value ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(RegSubcard)
                                        .border(1.dp, RegOutline, RoundedCornerShape(10.dp))
                                        .combinedClickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { editingValue = value },
                                            onLongClick = {
                                                copyToClipboard("${value.name ?: "(Default)"} = ${value.value}")
                                            },
                                        )
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            value.name ?: stringResource(R.string.registry_default_value),
                                            color = RegTextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${value.type}: ${value.value.ifEmpty { "(empty)" }}",
                                            color = RegTextSecondary,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    // ── Диалог добавления ключа ──
    if (showAddKeyDialog) {
        var keyName by remember { mutableStateOf("") }
        RegEditorOverlay(onDismiss = { showAddKeyDialog = false }) {
            PopupDialog(
                title = stringResource(R.string.registry_add_key),
                message = "Path: ${if (currentPath.isEmpty()) "\\" else currentPath}",
                icon = Icons.Outlined.CreateNewFolder,
                confirmLabel = stringResource(R.string.common_ui_ok),
                onConfirm = {
                    val trimmed = keyName.trim()
                    if (trimmed.isNotEmpty() && !trimmed.contains("\\")) {
                        val newKey = if (currentPath.isEmpty()) trimmed else "$currentPath\\$trimmed"
                        val fileKey = filePathFor(newKey)
                        scope.launch(Dispatchers.IO) {
                            WineRegistryEditor(regFile).use { reg ->
                                reg.setStringValue(fileKey, null, "")
                            }
                            refreshKey++
                            reloadChildrenForce(currentPath)
                        }
                    }
                    showAddKeyDialog = false
                },
                onCancel = { showAddKeyDialog = false },
                accentColor = RegAccent,
                content = {
                    BasicTextField(
                        value = keyName,
                        onValueChange = { keyName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(RegSubcard)
                            .border(1.dp, RegOutline, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = RegTextPrimary,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = SolidColor(RegAccent),
                        singleLine = true,
                    )
                },
            )
        }
    }

    // ── Диалог добавления/редактирования значения ──
    if (addingValue || editingValue != null) {
        val existing = editingValue
        var valueName by remember(existing) { mutableStateOf(existing?.name ?: "") }
        var valueType by remember(existing) { mutableStateOf(existing?.type?.takeIf { it in REG_VALUE_TYPES } ?: "String") }
        var valueText by remember(existing) { mutableStateOf(existing?.value ?: "") }
        var typeExpanded by remember { mutableStateOf(false) }

        RegEditorOverlay(onDismiss = { addingValue = false; editingValue = null }) {
            PopupDialog(
                title = if (existing != null) stringResource(R.string.registry_edit_value) else stringResource(R.string.registry_add_value),
                message = null,
                icon = Icons.Outlined.Tune,
                confirmLabel = stringResource(R.string.common_ui_ok),
                onConfirm = {
                    scope.launch(Dispatchers.IO) {
                        try {
                            WineRegistryEditor(regFile).use { reg ->
                                val name = valueName.trim().ifEmpty { null }
                                val fileKey = filePathFor(currentPath)
                                when (valueType) {
                                    "Dword" -> {
                                        val clean = valueText.trim().removePrefix("0x")
                                        if (clean.isEmpty()) throw IllegalArgumentException("empty")
                                        reg.setDwordValue(fileKey, name, java.lang.Long.decode("0x" + clean).toInt())
                                    }
                                    "Qword" -> {
                                        val clean = valueText.filter { it.isDigit() || it in "abcdefABCDEF" }
                                        if (clean.isEmpty()) throw IllegalArgumentException("empty")
                                        reg.setQwordValue(fileKey, name, java.lang.Long.parseUnsignedLong(clean, 16))
                                    }
                                    "Hex" -> {
                                        val clean = valueText.filter { it.isDigit() || it in "abcdefABCDEF" }
                                        if (clean.isEmpty()) throw IllegalArgumentException("empty")
                                        reg.setHexValue(fileKey, name, clean)
                                    }
                                    "ExpandString" -> {
                                        val clean = valueText.filter { it.isDigit() || it in "abcdefABCDEF" }
                                        if (clean.isEmpty()) throw IllegalArgumentException("empty")
                                        reg.setTypedHexValue(fileKey, name, "hex(2):", clean)
                                    }
                                    "MultiString" -> {
                                        val clean = valueText.filter { it.isDigit() || it in "abcdefABCDEF" }
                                        if (clean.isEmpty()) throw IllegalArgumentException("empty")
                                        reg.setTypedHexValue(fileKey, name, "hex(7):", clean)
                                    }
                                    else -> reg.setStringValue(fileKey, name, valueText)
                                }
                            }
                            refreshKey++
                            onToast(ctx.getString(R.string.registry_saved))
                        } catch (e: Exception) {
                            onToast(ctx.getString(R.string.registry_invalid_hex))
                        }
                    }
                    addingValue = false
                    editingValue = null
                },
                onCancel = { addingValue = false; editingValue = null },
                accentColor = RegAccent,
                footer = {
                    if (existing != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RegEditorTextAction(
                                label = stringResource(R.string.registry_delete_value),
                                textColor = RegDanger,
                                onClick = {
                                    deleteValueConfirm = existing
                                    addingValue = false
                                    editingValue = null
                                },
                            )
                        }
                    }
                },
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RegEditorTextField(
                            value = valueName,
                            onValueChange = { valueName = it },
                            label = stringResource(R.string.registry_value_name),
                            enabled = existing == null,
                        )
                        Box {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(RegSubcard)
                                    .border(1.dp, RegOutline, RoundedCornerShape(8.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { typeExpanded = true },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${stringResource(R.string.registry_type)}: $valueType",
                                        color = RegTextPrimary,
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(Icons.Filled.ArrowDropDown, null, tint = RegTextSecondary)
                                }
                            }
                            DropdownMenu(
                                expanded = typeExpanded,
                                onDismissRequest = { typeExpanded = false },
                                containerColor = RegCard,
                            ) {
                                REG_VALUE_TYPES.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type, color = RegTextPrimary) },
                                        onClick = { valueType = type; typeExpanded = false },
                                    )
                                }
                            }
                        }
                        RegEditorTextField(
                            value = valueText,
                            onValueChange = { valueText = it },
                            label = stringResource(R.string.registry_value),
                            monospace = true,
                            singleLine = valueType == "String",
                        )
                    }
                },
            )
        }
    }

    // ── Диалог спуфинга CPU ──
    if (showSpoofDialog) {
        val autoPreset = if (wineArch.equals("arm64ec", true)) PRESET_ARM else PRESET_INTEL
        var spoofIdentifier by remember { mutableStateOf(sp.getString(PREFS_SPOOF_IDENTIFIER, null) ?: autoPreset.identifier) }
        var spoofVendor by remember { mutableStateOf(sp.getString(PREFS_SPOOF_VENDOR, null) ?: autoPreset.vendor) }
        var spoofMhz by remember { mutableStateOf(sp.getString(PREFS_SPOOF_MHZ, null) ?: autoPreset.mhz.toString()) }
        var spoofFeatureSet by remember { mutableStateOf(sp.getString(PREFS_SPOOF_FEATURESET, null) ?: autoPreset.featureSet) }
        var spoofPresetName by remember { mutableStateOf(if (sp.getString(PREFS_SPOOF_IDENTIFIER, null) == null) autoPreset.name else "") }

        fun applyPreset(preset: CpuSpoofPreset) {
            spoofIdentifier = preset.identifier
            spoofVendor = preset.vendor
            spoofMhz = preset.mhz.toString()
            spoofFeatureSet = preset.featureSet
            spoofPresetName = preset.name
        }

        RegEditorOverlay(onDismiss = { showSpoofDialog = false }) {
            PopupDialog(
                title = stringResource(R.string.registry_cpu_spoof),
                message = stringResource(R.string.registry_cpu_spoof_hint, wineArch),
                icon = Icons.Outlined.Memory,
                confirmLabel = stringResource(R.string.common_ui_apply),
                onConfirm = {
                    scope.launch(Dispatchers.IO) {
                        WineRegistryEditor(regFile).use { reg ->
                            val numCpus = Runtime.getRuntime().availableProcessors()
                            for (i in 0 until numCpus) {
                                val key = "Hardware\\Description\\System\\CentralProcessor\\$i"
                                reg.setStringValue(key, "Identifier", spoofIdentifier)
                                reg.setStringValue(key, "VendorIdentifier", spoofVendor)
                                reg.setDwordValue(key, "~MHz", spoofMhz.toIntOrNull() ?: 0)
                                reg.setDwordValue(key, "FeatureSet", spoofFeatureSet.toLongOrNull(16)?.toInt() ?: 0)
                            }
                        }
                        refreshKey++
                    }
                    sp.edit()
                        .putString(PREFS_SPOOF_IDENTIFIER, spoofIdentifier)
                        .putString(PREFS_SPOOF_VENDOR, spoofVendor)
                        .putString(PREFS_SPOOF_MHZ, spoofMhz)
                        .putString(PREFS_SPOOF_FEATURESET, spoofFeatureSet)
                        .apply()
                    showSpoofDialog = false
                    onToast(ctx.getString(R.string.registry_cpu_spoof_done))
                },
                onCancel = { showSpoofDialog = false },
                accentColor = RegAccent,
                footer = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RegEditorTextAction(
                            label = stringResource(R.string.registry_cpu_spoof_clear),
                            textColor = RegDanger,
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    WineRegistryEditor(regFile).use { reg ->
                                        val numCpus = Runtime.getRuntime().availableProcessors()
                                        for (i in 0 until numCpus) {
                                            val key = "Hardware\\Description\\System\\CentralProcessor\\$i"
                                            reg.removeValue(key, "Identifier")
                                            reg.removeValue(key, "VendorIdentifier")
                                            reg.removeValue(key, "~MHz")
                                            reg.removeValue(key, "FeatureSet")
                                        }
                                    }
                                    refreshKey++
                                }
                                sp.edit()
                                    .remove(PREFS_SPOOF_IDENTIFIER)
                                    .remove(PREFS_SPOOF_VENDOR)
                                    .remove(PREFS_SPOOF_MHZ)
                                    .remove(PREFS_SPOOF_FEATURESET)
                                    .apply()
                                showSpoofDialog = false
                                onToast(ctx.getString(R.string.registry_cpu_spoof_cleared))
                            },
                        )
                        RegEditorTextAction(
                            label = stringResource(R.string.common_ui_apply),
                            textColor = RegAccent,
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    WineRegistryEditor(regFile).use { reg ->
                                        val numCpus = Runtime.getRuntime().availableProcessors()
                                        for (i in 0 until numCpus) {
                                            val key = "Hardware\\Description\\System\\CentralProcessor\\$i"
                                            reg.setStringValue(key, "Identifier", spoofIdentifier)
                                            reg.setStringValue(key, "VendorIdentifier", spoofVendor)
                                            reg.setDwordValue(key, "~MHz", spoofMhz.toIntOrNull() ?: 0)
                                            reg.setDwordValue(key, "FeatureSet", spoofFeatureSet.toLongOrNull(16)?.toInt() ?: 0)
                                        }
                                    }
                                    refreshKey++
                                }
                                sp.edit()
                                    .putString(PREFS_SPOOF_IDENTIFIER, spoofIdentifier)
                                    .putString(PREFS_SPOOF_VENDOR, spoofVendor)
                                    .putString(PREFS_SPOOF_MHZ, spoofMhz)
                                    .putString(PREFS_SPOOF_FEATURESET, spoofFeatureSet)
                                    .apply()
                                showSpoofDialog = false
                                onToast(ctx.getString(R.string.registry_cpu_spoof_done))
                            },
                        )
                        RegEditorTextAction(
                            label = stringResource(R.string.common_ui_cancel),
                            textColor = RegTextSecondary,
                            onClick = { showSpoofDialog = false },
                        )
                    }
                },
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RegEditorPresetChip(
                                label = PRESET_ARM.name,
                                selected = spoofPresetName == PRESET_ARM.name,
                                onClick = { applyPreset(PRESET_ARM) },
                            )
                            RegEditorPresetChip(
                                label = PRESET_INTEL.name,
                                selected = spoofPresetName == PRESET_INTEL.name,
                                onClick = { applyPreset(PRESET_INTEL) },
                            )
                        }
                        RegEditorTextField(
                            value = spoofIdentifier,
                            onValueChange = { spoofIdentifier = it; spoofPresetName = "" },
                            label = "Identifier",
                        )
                        RegEditorTextField(
                            value = spoofVendor,
                            onValueChange = { spoofVendor = it; spoofPresetName = "" },
                            label = "VendorIdentifier",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RegEditorTextField(
                                value = spoofMhz,
                                onValueChange = { spoofMhz = it.filter { c -> c.isDigit() }; spoofPresetName = "" },
                                label = "~MHz",
                                modifier = Modifier.weight(1f),
                                keyboardType = KeyboardType.Number,
                            )
                            RegEditorTextField(
                                value = spoofFeatureSet,
                                onValueChange = { spoofFeatureSet = it.filter { c -> c.isDigit() || c in "abcdefABCDEF" }; spoofPresetName = "" },
                                label = "FeatureSet (hex)",
                                modifier = Modifier.weight(1f),
                                monospace = true,
                            )
                        }
                    }
                },
            )
        }
    }

    // ── Подтверждение удаления ключа ──
    deleteKeyConfirm?.let { keyPath ->
        RegEditorOverlay(onDismiss = { deleteKeyConfirm = null }) {
            PopupDialog(
                title = stringResource(R.string.registry_delete_key),
                message = stringResource(R.string.registry_confirm_delete_key, keyPath),
                icon = Icons.Outlined.DeleteForever,
                confirmLabel = stringResource(R.string.common_ui_ok),
                onConfirm = {
                    val fileKey = filePathFor(keyPath)
                    scope.launch(Dispatchers.IO) {
                        WineRegistryEditor(regFile).use { reg ->
                            reg.removeKey(fileKey, true)
                        }
                        refreshKey++
                        // Перечитываем родителя, чтобы удалённый ключ исчез из дерева
                        reloadChildrenForce(keyPath.substringBeforeLast("\\", ""))
                    }
                    deleteKeyConfirm = null
                    showValuesDialog = false
                },
                onCancel = { deleteKeyConfirm = null },
                accentColor = RegDanger,
            )
        }
    }

    // ── Подтверждение удаления значения ──
    deleteValueConfirm?.let { value ->
        RegEditorOverlay(onDismiss = { deleteValueConfirm = null }) {
            PopupDialog(
                title = stringResource(R.string.registry_delete_value),
                message = stringResource(R.string.registry_confirm_delete_value, value.name ?: "(Default)"),
                icon = Icons.Outlined.Delete,
                confirmLabel = stringResource(R.string.common_ui_ok),
                onConfirm = {
                    val fileKey = filePathFor(currentPath)
                    scope.launch(Dispatchers.IO) {
                        WineRegistryEditor(regFile).use { reg ->
                            reg.removeValue(fileKey, value.name)
                        }
                        refreshKey++
                    }
                    deleteValueConfirm = null
                },
                onCancel = { deleteValueConfirm = null },
                accentColor = RegDanger,
            )
        }
    }
}

// ── Hive card (Computer root screen) ───────────────────────────────────
@Composable
private fun HiveCard(
    hive: RegistryHive,
    exists: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RegCard)
            .border(1.dp, RegOutline, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(RegIconBox),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                hiveIconFor(hive.key),
                null,
                tint = RegAccent,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                hive.key,
                color = RegTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${hive.description} · ${hive.fileName}",
                color = RegTextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!exists) {
            Text(
                stringResource(R.string.registry_file_not_found),
                color = RegDanger,
                fontSize = 10.sp,
                maxLines = 2,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            null,
            tint = RegTextSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ── Reusable text field with file-manager styling ──────────────────────
@Composable
private fun RegEditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    monospace: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = RegTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(RegSubcard)
                .border(1.dp, RegOutline, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = if (enabled) RegTextPrimary else RegTextSecondary,
                fontSize = 14.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
            cursorBrush = SolidColor(RegAccent),
            singleLine = singleLine,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
    }
}

// ── Reusable icon buttons (mirrors file manager) ───────────────────────
@Composable
private fun RegEditorIconButton(
    image: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(RegSubcard)
            .border(1.dp, RegOutline, RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = image,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RegEditorSmallIconButton(
    image: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(RegSubcard)
            .border(1.dp, RegOutline, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = image,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun RegEditorActionButton(
    modifier: Modifier = Modifier,
    image: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentTint = if (enabled) tint else tint.copy(alpha = 0.4f)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) RegSubcard else RegSubcard.copy(alpha = 0.5f))
            .border(
                1.dp,
                if (enabled) RegOutline else RegOutline.copy(alpha = 0.4f),
                RoundedCornerShape(9.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = image,
                contentDescription = null,
                tint = contentTint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = contentTint,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Плавающая панель действий (внизу экрана) ──────────────────────────
@Composable
private fun RegEditorBottomBar(
    modifier: Modifier = Modifier,
    navBarPadding: PaddingValues,
    enabled: Boolean = true,
    onAddKey: (() -> Unit)? = null,
    onAddValue: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    onEditValue: (() -> Unit)? = null,
    onDeleteKey: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(RegBg.copy(alpha = 0.96f))
            .border(
                width = 1.dp,
                color = RegOutline,
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
            )
            .padding(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = 8.dp + navBarPadding.calculateBottomPadding(),
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onAddKey?.let {
            RegEditorActionButton(
                modifier = Modifier.width(80.dp),
                image = Icons.Outlined.CreateNewFolder,
                label = stringResource(R.string.registry_add_key),
                tint = RegAccent,
                enabled = enabled,
                onClick = it,
            )
        }
        onAddValue?.let {
            RegEditorActionButton(
                modifier = Modifier.width(80.dp),
                image = Icons.Outlined.Add,
                label = stringResource(R.string.registry_add_value),
                tint = RegAccent,
                enabled = enabled,
                onClick = it,
            )
        }
        onImport?.let {
            RegEditorActionButton(
                modifier = Modifier.width(80.dp),
                image = Icons.Outlined.FileOpen,
                label = stringResource(R.string.registry_import),
                tint = RegTextSecondary,
                enabled = enabled,
                onClick = it,
            )
        }
        onExport?.let {
            RegEditorActionButton(
                modifier = Modifier.width(80.dp),
                image = Icons.Outlined.FileDownload,
                label = stringResource(R.string.registry_export),
                tint = RegTextSecondary,
                enabled = enabled,
                onClick = it,
            )
        }
        onEditValue?.let {
            RegEditorActionButton(
                modifier = Modifier.width(80.dp),
                image = Icons.Outlined.Edit,
                label = stringResource(R.string.registry_edit_value),
                tint = RegAccent,
                enabled = enabled,
                onClick = it,
            )
        }
        onDeleteKey?.let {
            RegEditorActionButton(
                modifier = Modifier.width(80.dp),
                image = Icons.Outlined.Delete,
                label = stringResource(R.string.registry_delete_key),
                tint = RegDanger,
                enabled = enabled,
                onClick = it,
            )
        }
    }
}

@Composable
private fun RegEditorSmallButton(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(RegSubcard)
            .border(1.dp, RegOutline, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RegEditorTextAction(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun RegEditorPresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) RegAccent.copy(alpha = 0.2f) else RegSubcard)
            .border(1.dp, if (selected) RegAccent else RegOutline, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) RegAccent else RegTextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ── Dialog overlay (centered modal wrapper) ────────────────────────────
@Composable
private fun RegEditorOverlay(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState())
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            content()
        }
    }
}

private fun hiveIconFor(key: String): ImageVector = when (key) {
    "HKEY_CLASSES_ROOT" -> Icons.Filled.Link
    "HKEY_CURRENT_USER" -> Icons.Filled.Person
    "HKEY_LOCAL_MACHINE" -> Icons.Filled.Computer
    "HKEY_USERS" -> Icons.Filled.People
    "HKEY_CURRENT_CONFIG" -> Icons.Filled.Settings
    else -> Icons.Filled.Folder
}

private fun decodeRegText(bytes: ByteArray): String {
    val start = if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) 2 else 0
    if (start == 2) return String(bytes, start, bytes.size - start, Charsets.UTF_16LE)
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    return String(bytes, Charsets.UTF_8)
}

// ── Hive 根界面导入：按 HKEY_* 前缀分发到对应 .reg 文件 ──────────────
private fun importRegToHives(rootDir: File?, regText: String) {
    if (rootDir == null) return
    val targets = LinkedHashMap<String, StringBuilder>()
    var currentHiveFile = "system.reg"
    for (rawLine in regText.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
        val line = rawLine.trim()
        if (line.startsWith("[") && line.endsWith("]")) {
            val keyPath = line.substring(1, line.length - 1)
            currentHiveFile = when {
                keyPath.startsWith("HKEY_CURRENT_USER") || keyPath.startsWith("HKEY_USERS") -> "user.reg"
                else -> "system.reg"
            }
        }
        targets.getOrPut(currentHiveFile) { StringBuilder() }.append(rawLine).append("\n")
    }
    for ((fileName, sb) in targets) {
        try {
            WineRegistryEditor(File(rootDir, ".wine/$fileName")).use { it.importRegFile(sb.toString()) }
        } catch (e: Exception) {
            // 单个 hive 导入失败不阻断其余 hive
        }
    }
}
