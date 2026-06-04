package com.winlator.cmod.feature.settings.containers;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.activity.ComponentActivity;
import androidx.activity.compose.setContent;
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SnippetFolder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.winlator.cmod.R
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.FileInfo
import com.winlator.cmod.runtime.display.XServerDisplayActivity
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.util.StringUtils
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import java.io.File
import java.util.Stack
import java.util.Locale

// ─── Palette matching app theme ─────────────────────────────────────
private val FileManagerBg = Color(0xFF18181D)
private val FileManagerCard = Color(0xFF1C1C2A)
private val FileManagerSubcard = Color(0xFF161622)
private val FileManagerOutline = Color(0xFF2A2A3A)
private val FileManagerIconBox = Color(0xFF242434)
private val FileManagerAccent = Color(0xFF1A9FFF)
private val FileManagerTextPrimary = Color(0xFFF0F4FF)
private val FileManagerTextSecondary = Color(0xFF7A8FA8)
private val FileManagerDanger = Color(0xFFFF7A88)

class ContainerFileManagerActivity : ComponentActivity() {

    private lateinit var container: Container
    private lateinit var manager: ContainerManager
    private val folderStack = Stack<FileInfo>()
    private var files by mutableStateOf(emptyList<FileInfo>())
    private var currentPath by mutableStateOf("")
    private var clipboard: ClipboardState? by mutableStateOf(null)
    private var showOverwriteDialog by mutableStateOf(false)
    private var selectedFiles by mutableStateOf(mutableSetOf<String>())
    private var isMultiSelectMode by mutableStateOf(false)
    private var showSelectedRenameDialog by mutableStateOf<FileInfo?>(null)
    private var showSelectedInfoDialog by mutableStateOf<FileInfo?>(null)

    data class ClipboardState(
        val files: List<FileInfo>,
        val cutMode: Boolean,
    )

    // Data class to reduce @Composable function parameter count (avoid DEX VerifyError)
    data class ScreenState(
        val containerName: String,
        val currentPath: String,
        val files: List<FileInfo>,
        val clipboard: ClipboardState?,
        val showOverwriteDialog: Boolean,
        val selectedFiles: Set<String>,
        val isMultiSelectMode: Boolean,
        val showSelectedRenameDialog: FileInfo?,
        val showSelectedInfoDialog: FileInfo?,
    )

    class ScreenCallbacks(
        val onBack: () -> Unit,
        val onOpen: (FileInfo) -> Unit,
        val onCopy: (FileInfo) -> Unit,
        val onCut: (FileInfo) -> Unit,
        val onRemove: (FileInfo) -> Unit,
        val onRename: (FileInfo) -> Unit,
        val onInfo: (FileInfo) -> Unit,
        val onHome: () -> Unit,
        val onNewFolder: () -> Unit,
        val onPaste: () -> Unit,
        val onClearClipboard: () -> Unit,
        val onDismissRenameDialog: () -> Unit,
        val onDismissOverwriteDialog: () -> Unit,
        val onConfirmOverwrite: () -> Unit,
        val onToggleSelect: (String) -> Unit,
        val onEnterMultiSelect: (FileInfo) -> Unit,
        val onExitMultiSelect: () -> Unit,
        val onMultiCopy: () -> Unit,
        val onMultiCut: () -> Unit,
        val onMultiRemove: () -> Unit,
        val onSelectedRename: () -> Unit,
        val onSelectedInfo: () -> Unit,
        val onPerformSelectedRename: (FileInfo, String) -> Unit,
        val onDismissSelectedRenameDialog: () -> Unit,
        val onDismissSelectedInfoDialog: () -> Unit,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immersive display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val containerId = intent.getIntExtra("container_id", -1)
        if (containerId == -1) {
            finish()
            return
        }

        manager = ContainerManager(this)
        container = manager.getContainerById(containerId) ?: run {
            finish()
            return
        }

        loadRootFiles()

        setContent {
            val state = ScreenState(
                containerName = container.name,
                currentPath = currentPath,
                files = files,
                clipboard = clipboard,
                showOverwriteDialog = showOverwriteDialog,
                selectedFiles = selectedFiles,
                isMultiSelectMode = isMultiSelectMode,
                showSelectedRenameDialog = showSelectedRenameDialog,
                showSelectedInfoDialog = showSelectedInfoDialog,
            )
            val callbacks = ScreenCallbacks(
                onBack = { navigateBack() },
                onOpen = { openFile(it) },
                onCopy = { copyFile(it) },
                onCut = { cutFile(it) },
                onRemove = { removeFile(it) },
                onRename = { renameFile(it) },
                onInfo = { showFileInfo(it) },
                onHome = { goHome() },
                onNewFolder = { createNewFolder() },
                onPaste = { pasteFiles() },
                onClearClipboard = { clearClipboard() },
                onDismissRenameDialog = { /* handled internally */ },
                onDismissOverwriteDialog = { showOverwriteDialog = false },
                onConfirmOverwrite = { performPaste() },
                onToggleSelect = { path -> toggleSelectFile(path) },
                onEnterMultiSelect = { file -> enterMultiSelectMode(file) },
                onExitMultiSelect = { exitMultiSelectMode() },
                onMultiCopy = { multiCopy() },
                onMultiCut = { multiCut() },
                onMultiRemove = { multiRemove() },
                onSelectedRename = { onSelectedRename() },
                onSelectedInfo = { onSelectedInfo() },
                onPerformSelectedRename = { file, newName -> performRename(file, newName) },
                onDismissSelectedRenameDialog = { showSelectedRenameDialog = null },
                onDismissSelectedInfoDialog = { showSelectedInfoDialog = null },
            )
            ContainerFileManagerScreen(state = state, callbacks = callbacks)
        }
    }

    private fun loadRootFiles() {
        folderStack.clear()
        files = manager.loadFiles(container, null)
        currentPath = container.name
    }

    private fun loadFilesInDirectory(parent: FileInfo) {
        files = manager.loadFiles(container, parent)
        currentPath = buildPathString()
    }

    private fun buildPathString(): String {
        val sb = StringBuilder()
        for (i in folderStack.indices) {
            if (i > 0) sb.append("\\")
            sb.append(folderStack[i].getDisplayName())
        }
        if (folderStack.size == 1) sb.append("\\")
        return sb.toString()
    }

    private fun navigateBack() {
        if (folderStack.isEmpty()) {
            finish()
        } else {
            folderStack.pop()
            if (folderStack.isEmpty()) {
                loadRootFiles()
            } else {
                loadFilesInDirectory(folderStack.peek())
            }
        }
    }

    private fun goHome() {
        loadRootFiles()
    }

    private fun openFile(file: FileInfo) {
        if (file.type == FileInfo.Type.DIRECTORY || file.type == FileInfo.Type.DRIVE) {
            folderStack.push(file)
            loadFilesInDirectory(file)
        } else {
            // Try to run the file
            val extension = file.name.substringAfterLast('.', "").lowercase()
            if (extension == "exe" || extension == "bat") {
                startActivity(
                    Intent(this, XServerDisplayActivity::class.java).apply {
                        putExtra("container_id", container.id)
                        putExtra("exec_path", file.path)
                    }
                )
            }
        }
    }

    private fun copyFile(file: FileInfo) {
        clipboard = ClipboardState(listOf(file), false)
        exitMultiSelectMode()
    }

    private fun cutFile(file: FileInfo) {
        clipboard = ClipboardState(listOf(file), true)
        exitMultiSelectMode()
    }

    private fun clearClipboard() {
        clipboard = null
    }

    private fun removeFile(file: FileInfo) {
        val fileObj = file.toFile()
        val success = FileUtils.delete(fileObj)
        if (success) {
            refreshCurrentDirectory()
        }
    }

    // ── Multi-select logic ──

    private fun enterMultiSelectMode(file: FileInfo) {
        isMultiSelectMode = true
        selectedFiles = mutableSetOf(file.path)
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        selectedFiles = mutableSetOf()
    }

    private fun toggleSelectFile(path: String) {
        val newSet = selectedFiles.toMutableSet()
        if (newSet.contains(path)) {
            newSet.remove(path)
        } else {
            newSet.add(path)
        }
        selectedFiles = newSet
        if (newSet.isEmpty()) {
            isMultiSelectMode = false
        }
    }

    private fun getSelectedFileInfos(): List<FileInfo> {
        return files.filter { selectedFiles.contains(it.path) }
    }

    private fun multiCopy() {
        val selected = getSelectedFileInfos()
        if (selected.isNotEmpty()) {
            clipboard = ClipboardState(selected, false)
        }
        exitMultiSelectMode()
    }

    private fun multiCut() {
        val selected = getSelectedFileInfos()
        if (selected.isNotEmpty()) {
            clipboard = ClipboardState(selected, true)
        }
        exitMultiSelectMode()
    }

    private fun multiRemove() {
        val selected = getSelectedFileInfos()
        for (file in selected) {
            FileUtils.delete(file.toFile())
        }
        exitMultiSelectMode()
        refreshCurrentDirectory()
    }

    private fun onSelectedRename() {
        val selected = getSelectedFileInfos()
        if (selected.size == 1) {
            showSelectedRenameDialog = selected[0]
        }
    }

    private fun onSelectedInfo() {
        val selected = getSelectedFileInfos()
        if (selected.size == 1) {
            showSelectedInfoDialog = selected[0]
        }
    }

    private fun renameFile(file: FileInfo) {
        // Handled via dialog in UI
    }

    private fun performRename(file: FileInfo, newName: String) {
        val success = file.renameTo(newName)
        if (success) {
            refreshCurrentDirectory()
        }
    }

    private fun createNewFolder() {
        if (folderStack.isEmpty()) return
        val parent = folderStack.peek().toFile()
        var name = "New folder"
        var counter = 1
        while (File(parent, name).exists()) {
            name = "New folder ($counter)"
            counter++
        }
        File(parent, name).mkdir()
        refreshCurrentDirectory()
    }

    private fun pasteFiles() {
        val clip = clipboard ?: return
        if (folderStack.isEmpty()) return

        val targetDir = folderStack.peek().toFile()

        // Check if trying to paste in the same directory
        for (src in clip.files) {
            val srcFile = src.toFile()
            val parentDir = srcFile.parentFile
            if (parentDir != null && parentDir == targetDir) {
                if (clip.cutMode) {
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.you_cannot_paste_files_here),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.there_already_file_with_that_name),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }
        }

        // Check if any file or directory already exists
        for (src in clip.files) {
            val srcFile = src.toFile()
            val targetFile = File(targetDir, srcFile.name)
            if (targetFile.exists()) {
                showOverwriteConfirmationDialog()
                return
            }
        }

        // No conflicts, proceed with paste
        performPaste()
    }

    private fun showOverwriteConfirmationDialog() {
        showOverwriteDialog = true
    }

    private fun performPaste() {
        val clip = clipboard ?: return
        val targetDir = folderStack.peek().toFile()
        for (src in clip.files) {
            val srcFile = src.toFile()
            val targetFile = File(targetDir, srcFile.name)
            if (srcFile.exists()) {
                if (srcFile.isDirectory) {
                    // Recursively copy/overwrite same-named items
                    FileUtils.copy(srcFile, targetFile)
                } else {
                    FileUtils.copy(srcFile, targetFile)
                }
                if (clip.cutMode) {
                    FileUtils.delete(srcFile)
                }
            }
        }
        clearClipboard()
        refreshCurrentDirectory()
    }

    private fun showFileInfo(file: FileInfo) {
        val size = if (file.type == FileInfo.Type.FILE) {
            StringUtils.formatBytes(file.getSize())
        } else if (file.type == FileInfo.Type.DIRECTORY) {
            "${file.getItemCount()} items"
        } else {
            "Drive"
        }
        // Simple toast for now
        android.widget.Toast.makeText(
            this,
            "${file.getDisplayName()}\n$size\n${file.path}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun refreshCurrentDirectory() {
        if (folderStack.isEmpty()) {
            loadRootFiles()
        } else {
            loadFilesInDirectory(folderStack.peek())
        }
    }

    override fun onBackPressed() {
        navigateBack()
    }
}

private fun getDirectorySize(dir: File): Long {
    var totalSize = 0L
    val files = dir.listFiles() ?: return 0L
    for (file in files) {
        if (file.isDirectory) {
            totalSize += getDirectorySize(file)
        } else {
            totalSize += file.length()
        }
    }
    return totalSize
}

@Composable
private fun ContainerFileManagerScreen(
    state: ContainerFileManagerActivity.ScreenState,
    callbacks: ContainerFileManagerActivity.ScreenCallbacks,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    var showConfirmDelete by remember { mutableStateOf<FileInfo?>(null) }
    var showRenameDialog by remember { mutableStateOf<FileInfo?>(null) }
    val gridState = rememberLazyGridState()
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Clear search when files change (new directory loaded)
    LaunchedEffect(state.files) {
        searchQuery = ""
        showSearch = false
        if (state.files.isNotEmpty()) {
            gridState.scrollToItem(0)
        }
    }

    val filteredFiles = if (searchQuery.isBlank()) state.files
        else state.files.filter { it.getDisplayName().contains(searchQuery, ignoreCase = true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FileManagerBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 16.dp,
                    top = statusBarPadding.calculateTopPadding(),
                    end = 16.dp,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
        ) {
        // ── Header ──
        if (state.isMultiSelectMode) {
            // Multi-select header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileManagerIconButton(
                    image = Icons.Outlined.Clear,
                    tint = FileManagerAccent,
                    onClick = callbacks.onExitMultiSelect,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Selected ${state.selectedFiles.size}",
                    color = FileManagerTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileManagerIconButton(
                image = Icons.AutoMirrored.Outlined.ArrowBack,
                tint = FileManagerAccent,
                onClick = callbacks.onBack,
            )
            Spacer(Modifier.width(8.dp))
            FileManagerIconButton(
                image = Icons.Outlined.Home,
                tint = FileManagerTextSecondary,
                onClick = callbacks.onHome,
            )
            Spacer(Modifier.width(12.dp))
            // ── Path / Search area ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // Search bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .alpha(if (showSearch) 1f else 0f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(FileManagerCard)
                        .border(1.dp, FileManagerAccent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = FileManagerAccent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = FileManagerTextPrimary,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = SolidColor(FileManagerAccent),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.common_ui_search_files),
                                    color = FileManagerTextSecondary,
                                    fontSize = 14.sp,
                                )
                            }
                            innerTextField()
                        },
                    )
                    if (searchQuery.isNotEmpty()) {
                        FileManagerSmallIconButton(
                            image = Icons.Outlined.Clear,
                            tint = FileManagerTextSecondary,
                            onClick = { searchQuery = "" },
                        )
                    }
                }
                // Path text
                Column(
                    modifier = Modifier
                        .alpha(if (showSearch) 0f else 1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = state.currentPath,
                        color = FileManagerTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.containerName,
                        color = FileManagerTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            FileManagerIconButton(
                image = Icons.Outlined.Search,
                tint = if (showSearch) FileManagerAccent else FileManagerTextSecondary,
                onClick = {
                    showSearch = !showSearch
                    if (showSearch) {
                        focusRequester.requestFocus()
                    } else {
                        searchQuery = ""
                        focusManager.clearFocus()
                    }
                },
            )
            Spacer(Modifier.width(6.dp))
            FileManagerIconButton(
                image = Icons.Outlined.CreateNewFolder,
                tint = FileManagerTextSecondary,
                onClick = callbacks.onNewFolder,
            )
        }
        } // end of else

        // ── Separator ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(FileManagerOutline),
        )
        Spacer(Modifier.height(8.dp))

        // ── File Grid ──
        if (filteredFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (searchQuery.isNotBlank()) stringResource(R.string.common_ui_no_match_found)
                        else stringResource(R.string.common_ui_no_items_to_display),
                    color = FileManagerTextSecondary,
                    fontSize = 16.sp,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = filteredFiles,
                    key = { file -> file.path },
                ) { file ->
                    val isSelected = state.selectedFiles.contains(file.path)
                    FileItemCard(
                        file = file,
                        isSelected = isSelected,
                        isMultiSelectMode = state.isMultiSelectMode,
                        onOpen = {
                            if (state.isMultiSelectMode) {
                                if (file.type != FileInfo.Type.DRIVE) {
                                    callbacks.onToggleSelect(file.path)
                                }
                            } else {
                                callbacks.onOpen(file)
                            }
                        },
                        onLongPress = if (file.type != FileInfo.Type.DRIVE) {
                            { callbacks.onEnterMultiSelect(file) }
                        } else {
                            {}
                        },
                        onCopy = { callbacks.onCopy(file) },
                        onCut = { callbacks.onCut(file) },
                        onRemove = { showConfirmDelete = file },
                        onRename = { showRenameDialog = file },
                        onInfo = { callbacks.onInfo(file) },
                    )
                }
            }
        }

        } // end of Column

        // ── Multi-select bottom action bar ──
        if (state.isMultiSelectMode && state.selectedFiles.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = navBarPadding.calculateBottomPadding() + 8.dp,
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(FileManagerCard)
                        .border(1.dp, FileManagerOutline, RoundedCornerShape(16.dp))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    // Copy
                    FileManagerActionButton(
                        modifier = Modifier.weight(1f),
                        image = Icons.Outlined.ContentCopy,
                        tint = FileManagerTextSecondary,
                        onClick = callbacks.onMultiCopy,
                    )
                    // Cut
                    FileManagerActionButton(
                        modifier = Modifier.weight(1f),
                        image = Icons.Outlined.ContentCut,
                        tint = FileManagerTextSecondary,
                        onClick = callbacks.onMultiCut,
                    )
                    // Remove
                    FileManagerActionButton(
                        modifier = Modifier.weight(1f),
                        image = Icons.Outlined.Delete,
                        tint = FileManagerDanger,
                        onClick = callbacks.onMultiRemove,
                    )
                    // Rename (only when single selection)
                    if (state.selectedFiles.size == 1) {
                        FileManagerActionButton(
                            modifier = Modifier.weight(1f),
                            image = Icons.Outlined.Edit,
                            tint = FileManagerTextSecondary,
                            onClick = callbacks.onSelectedRename,
                        )
                    }
                    // Info (only when single selection)
                    if (state.selectedFiles.size == 1) {
                        FileManagerActionButton(
                            modifier = Modifier.weight(1f),
                            image = Icons.Outlined.Info,
                            tint = FileManagerTextSecondary,
                            onClick = callbacks.onSelectedInfo,
                        )
                    }
                }
            }
        }

        // ── Paste FAB (fixed bottom-right) ──
        if (state.clipboard != null && !state.isMultiSelectMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 16.dp,
                        bottom = navBarPadding.calculateBottomPadding() + 16.dp,
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(FileManagerAccent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = callbacks.onPaste,
                        )
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.common_ui_paste),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    } // end of Box

    // ── Confirm Delete Dialog ──
    showConfirmDelete?.let { file ->
        DialogOverlay(onDismiss = { showConfirmDelete = null }) {
            PopupDialog(
                title = stringResource(R.string.common_ui_remove),
                message = stringResource(R.string.do_you_want_to_remove_this_file),
                confirmLabel = stringResource(R.string.common_ui_remove),
                onConfirm = {
                    val f = file.toFile()
                    FileUtils.delete(f)
                    showConfirmDelete = null
                    callbacks.onClearClipboard()
                    callbacks.onHome() // refresh
                },
                onCancel = { showConfirmDelete = null },
                accentColor = FileManagerDanger,
            )
        }
    }

    // ── Rename Dialog ──
    showRenameDialog?.let { file ->
        var newName by remember { mutableStateOf(file.getDisplayName()) }
        DialogOverlay(onDismiss = { showRenameDialog = null }) {
            PopupDialog(
                title = stringResource(R.string.common_ui_rename),
                message = null,
                confirmLabel = stringResource(R.string.common_ui_ok),
                onConfirm = {
                    if (newName.isNotBlank()) {
                        file.renameTo(newName)
                    }
                    showRenameDialog = null
                    callbacks.onClearClipboard()
                    callbacks.onHome()
                },
                onCancel = { showRenameDialog = null },
                accentColor = FileManagerAccent,
                content = {
                    BasicTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(FileManagerSubcard)
                            .border(1.dp, FileManagerOutline, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = FileManagerTextPrimary,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = SolidColor(FileManagerAccent),
                        singleLine = true,
                    )
                },
            )
        }
    }

    // ── Confirm Overwrite Dialog ──
    if (state.showOverwriteDialog) {
        DialogOverlay(onDismiss = { callbacks.onDismissOverwriteDialog() }) {
            PopupDialog(
                title = stringResource(R.string.confirm_overwrite),
                message = stringResource(R.string.file_or_directory_already_exists_overwrite),
                confirmLabel = stringResource(R.string.common_ui_ok),
                onConfirm = {
                    callbacks.onConfirmOverwrite()
                    callbacks.onDismissOverwriteDialog()
                },
                onCancel = { callbacks.onDismissOverwriteDialog() },
                accentColor = FileManagerAccent,
            )
        }
    }

    // ── Selected Rename Dialog ──
    state.showSelectedRenameDialog?.let { file ->
        var newName by remember { mutableStateOf(file.getDisplayName()) }
        DialogOverlay(onDismiss = { callbacks.onDismissSelectedRenameDialog() }) {
            PopupDialog(
                title = stringResource(R.string.common_ui_rename),
                message = null,
                confirmLabel = stringResource(R.string.common_ui_ok),
                onConfirm = {
                    if (newName.isNotBlank()) {
                        callbacks.onPerformSelectedRename(file, newName)
                    }
                    callbacks.onDismissSelectedRenameDialog()
                },
                onCancel = { callbacks.onDismissSelectedRenameDialog() },
                accentColor = FileManagerAccent,
                content = {
                    BasicTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(FileManagerSubcard)
                            .border(1.dp, FileManagerOutline, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = FileManagerTextPrimary,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = SolidColor(FileManagerAccent),
                        singleLine = true,
                    )
                },
            )
        }
    }

    // ── Selected Info Dialog ──
    state.showSelectedInfoDialog?.let { file ->
        val fileObj = file.toFile()
        val sizeText = when (file.type) {
            FileInfo.Type.FILE -> StringUtils.formatBytes(fileObj.length())
            FileInfo.Type.DIRECTORY -> StringUtils.formatBytes(getDirectorySize(fileObj))
            else -> "Drive"
        }
        val lastModified = fileObj.lastModified()
        val dateText = if (lastModified > 0) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date(lastModified))
        } else {
            "N/A"
        }
        val infoMessage = "${stringResource(R.string.common_ui_path)}: ${file.path}\n${stringResource(R.string.common_ui_size)}: $sizeText\n${stringResource(R.string.common_ui_modified)}: $dateText"
        DialogOverlay(onDismiss = { callbacks.onDismissSelectedInfoDialog() }) {
            PopupDialog(
                title = file.getDisplayName(),
                message = infoMessage,
                confirmLabel = stringResource(R.string.common_ui_close),
                onConfirm = { callbacks.onDismissSelectedInfoDialog() },
                onCancel = null,
                accentColor = FileManagerAccent,
            )
        }
    }
}

@Composable
private fun FileItemCard(
    file: FileInfo,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    onOpen: () -> Unit,
    onLongPress: () -> Unit = {},
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
) {
    val isDirectory = file.type == FileInfo.Type.DIRECTORY || file.type == FileInfo.Type.DRIVE
    val icon = when (file.type) {
        FileInfo.Type.DRIVE -> Icons.Outlined.SnippetFolder
        FileInfo.Type.DIRECTORY -> Icons.Outlined.SnippetFolder
        else -> Icons.Outlined.PlayArrow
    }
    val iconTint = when {
        file.type == FileInfo.Type.DRIVE -> FileManagerAccent
        file.type == FileInfo.Type.DIRECTORY -> Color(0xFFF0C040)
        else -> FileManagerTextSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) FileManagerAccent.copy(alpha = 0.15f) else FileManagerCard
            )
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) FileManagerAccent else FileManagerOutline,
                shape = RoundedCornerShape(12.dp),
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
                onLongClick = if (!isMultiSelectMode) onLongPress else null,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(FileManagerIconBox),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = file.getDisplayName(),
            color = FileManagerTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FileManagerIconButton(
    image: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(FileManagerSubcard)
            .border(1.dp, FileManagerOutline, RoundedCornerShape(8.dp))
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
private fun FileManagerSmallIconButton(
    image: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(FileManagerSubcard)
            .border(1.dp, FileManagerOutline, RoundedCornerShape(6.dp))
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
private fun FileManagerActionButton(
    modifier: Modifier = Modifier,
    image: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(FileManagerSubcard)
            .border(1.dp, FileManagerOutline, RoundedCornerShape(9.dp))
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
private fun FileManagerSmallButton(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(FileManagerSubcard)
            .border(1.dp, FileManagerOutline, RoundedCornerShape(6.dp))
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

// ── Dialog Overlay (centered modal wrapper) ──
@Composable
private fun DialogOverlay(
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
