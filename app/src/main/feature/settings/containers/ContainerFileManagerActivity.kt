package com.winlator.cmod.feature.settings.containers;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.activity.ComponentActivity;
import androidx.activity.compose.setContent;
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SnippetFolder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

    data class ClipboardState(
        val files: List<FileInfo>,
        val cutMode: Boolean,
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
            ContainerFileManagerScreen(
                containerName = container.name,
                currentPath = currentPath,
                files = files,
                clipboard = clipboard,
                showOverwriteDialog = showOverwriteDialog,
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
            )
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
    }

    private fun cutFile(file: FileInfo) {
        clipboard = ClipboardState(listOf(file), true)
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

@Composable
private fun ContainerFileManagerScreen(
    containerName: String,
    currentPath: String,
    files: List<FileInfo>,
    clipboard: ContainerFileManagerActivity.ClipboardState?,
    showOverwriteDialog: Boolean,
    onBack: () -> Unit,
    onOpen: (FileInfo) -> Unit,
    onCopy: (FileInfo) -> Unit,
    onCut: (FileInfo) -> Unit,
    onRemove: (FileInfo) -> Unit,
    onRename: (FileInfo) -> Unit,
    onInfo: (FileInfo) -> Unit,
    onHome: () -> Unit,
    onNewFolder: () -> Unit,
    onPaste: () -> Unit,
    onClearClipboard: () -> Unit,
    onDismissRenameDialog: () -> Unit,
    onDismissOverwriteDialog: () -> Unit,
    onConfirmOverwrite: () -> Unit,
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
    LaunchedEffect(files) {
        searchQuery = ""
        showSearch = false
        if (files.isNotEmpty()) {
            gridState.scrollToItem(0)
        }
    }

    val filteredFiles = if (searchQuery.isBlank()) files
        else files.filter { it.getDisplayName().contains(searchQuery, ignoreCase = true) }

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileManagerIconButton(
                image = Icons.AutoMirrored.Outlined.ArrowBack,
                tint = FileManagerAccent,
                onClick = onBack,
            )
            Spacer(Modifier.width(8.dp))
            FileManagerIconButton(
                image = Icons.Outlined.Home,
                tint = FileManagerTextSecondary,
                onClick = onHome,
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
                        text = currentPath,
                        color = FileManagerTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = containerName,
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
                onClick = onNewFolder,
            )
        }

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
                    FileItemCard(
                        file = file,
                        onOpen = { onOpen(file) },
                        onCopy = { onCopy(file) },
                        onCut = { onCut(file) },
                        onRemove = { showConfirmDelete = file },
                        onRename = { showRenameDialog = file },
                        onInfo = { onInfo(file) },
                    )
                }
            }
        }

        } // end of Column

        // ── Paste FAB (fixed bottom-right) ──
        if (clipboard != null) {
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
                            onClick = onPaste,
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
        PopupDialog(
            title = stringResource(R.string.common_ui_remove),
            message = stringResource(R.string.do_you_want_to_remove_this_file),
            confirmLabel = stringResource(R.string.common_ui_remove),
            onConfirm = {
                val f = file.toFile()
                FileUtils.delete(f)
                showConfirmDelete = null
                onClearClipboard()
                onHome() // refresh
            },
            onCancel = { showConfirmDelete = null },
            accentColor = FileManagerDanger,
        )
    }

    // ── Rename Dialog ──
    showRenameDialog?.let { file ->
        var newName by remember { mutableStateOf(file.getDisplayName()) }
        PopupDialog(
            title = stringResource(R.string.common_ui_rename),
            message = "",
            confirmLabel = stringResource(R.string.common_ui_ok),
            onConfirm = {
                if (newName.isNotBlank()) {
                    file.renameTo(newName)
                }
                showRenameDialog = null
                onClearClipboard()
                onHome()
            },
            onCancel = { showRenameDialog = null },
            accentColor = FileManagerAccent,
        )
    }

    // ── Confirm Overwrite Dialog ──
    if (showOverwriteDialog) {
        PopupDialog(
            title = stringResource(R.string.confirm_overwrite),
            message = stringResource(R.string.file_or_directory_already_exists_overwrite),
            confirmLabel = stringResource(R.string.common_ui_ok),
            onConfirm = {
                onConfirmOverwrite()
                onDismissOverwriteDialog()
            },
            onCancel = { onDismissOverwriteDialog() },
            accentColor = FileManagerAccent,
        )
    }
}

@Composable
private fun FileItemCard(
    file: FileInfo,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
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
    val nameFontSize = when {
        file.getDisplayName().length > 30 -> 10.sp
        file.getDisplayName().length > 20 -> 11.sp
        else -> 12.sp
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(FileManagerCard)
            .border(1.dp, FileManagerOutline, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            )
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FileManagerIconBox),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Box {
                FileManagerSmallIconButton(
                    image = Icons.Outlined.MoreVert,
                    tint = FileManagerTextSecondary,
                    onClick = { menuExpanded = true },
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = FileManagerCard,
                ) {
                    if (!isDirectory || file.type != FileInfo.Type.DRIVE) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_ui_copy), color = FileManagerTextPrimary) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, tint = FileManagerTextSecondary) },
                            onClick = {
                                menuExpanded = false
                                onCopy()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_ui_cut), color = FileManagerTextPrimary) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCut, null, tint = FileManagerTextSecondary) },
                            onClick = {
                                menuExpanded = false
                                onCut()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_ui_rename), color = FileManagerTextPrimary) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null, tint = FileManagerTextSecondary) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_ui_info), color = FileManagerTextPrimary) },
                        leadingIcon = { Icon(Icons.Outlined.Info, null, tint = FileManagerTextSecondary) },
                        onClick = {
                            menuExpanded = false
                            onInfo()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_ui_remove), color = FileManagerDanger) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = FileManagerDanger) },
                        onClick = {
                            menuExpanded = false
                            onRemove()
                        },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = file.getDisplayName(),
                color = FileManagerTextPrimary,
                fontSize = nameFontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
