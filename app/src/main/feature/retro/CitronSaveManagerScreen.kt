package com.winlator.cmod.feature.retro

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CitronSaveManagerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<SaveRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var restoreTarget by remember { mutableStateOf<CitronSaveManager.CitronSaveEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<CitronSaveManager.CitronSaveEntry?>(null) }

    fun refresh() {
        scope.launch(Dispatchers.IO) { rows = loadSaveRows(context) }
    }

    LaunchedEffect(Unit) {
        loading = true
        rows = withContext(Dispatchers.IO) { loadSaveRows(context) }
        loading = false
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101018))) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.ArrowBack, "back", tint = Color.White)
                }
                Text(
                    stringResource(R.string.retro_scr_switch_saves),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            HorizontalDivider(color = Color(0xFF2A3344))
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF4FC3F7))
                }
                rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.retro_scr_save_no_saves),
                        color = Color(0xFF93A6BC),
                        fontSize = 14.sp,
                    )
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    items(rows) { row ->
                        SaveCard(
                            entry = row.entry,
                            displayName = row.name,
                            onBackup = {
                                // Backups go to the public Download folder, which needs
                                // All-files access on Android 11+. Request it if missing.
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                                    !android.os.Environment.isExternalStorageManager()
                                ) {
                                    Toast.makeText(
                                        context,
                                        R.string.common_ui_grant_all_files_access,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    runCatching {
                                        (context as? android.app.Activity)?.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                android.net.Uri.parse("package:${context.packageName}"),
                                            ),
                                        )
                                    }
                                } else {
                                    scope.launch(Dispatchers.IO) {
                                        val dest = CitronSaveManager.backup(context, row.entry.titleId, row.name)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                if (dest != null) {
                                                    context.getString(R.string.retro_scr_save_backed_up, dest.absolutePath)
                                                } else {
                                                    context.getString(R.string.retro_scr_save_not_found)
                                                },
                                                if (dest != null) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                        rows = loadSaveRows(context)
                                    }
                                }
                            },
                            onRestore = { restoreTarget = row.entry },
                            onDelete = { deleteTarget = row.entry },
                            onCopyPath = { copyPath(context, row.entry.folder.absolutePath) },
                        )
                    }
                }
            }
        }
    }

    if (restoreTarget != null) {
        RestoreDialog(
            titleId = restoreTarget!!.titleId,
            context = context,
            onDismiss = { restoreTarget = null },
        ) { backup ->
            scope.launch(Dispatchers.IO) {
                val ok = CitronSaveManager.restore(context, restoreTarget!!.titleId, backup)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        if (ok) R.string.retro_scr_save_restored else R.string.retro_scr_save_not_found,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                rows = loadSaveRows(context)
                restoreTarget = null
            }
        }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.retro_scr_save_delete_title)) },
            text = { Text(stringResource(R.string.retro_scr_save_confirm_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val ok = CitronSaveManager.delete(deleteTarget!!.titleId, context)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                if (ok) R.string.retro_scr_save_deleted else R.string.retro_scr_save_not_found,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        rows = loadSaveRows(context)
                        deleteTarget = null
                    }
                }) { Text(stringResource(R.string.retro_scr_save_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SaveCard(
    entry: CitronSaveManager.CitronSaveEntry,
    displayName: String,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF1A2233), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(displayName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(entry.titleId, color = Color(0xFF7F8FA6), fontSize = 11.sp)
        Text(
            stringResource(
                R.string.retro_scr_save_meta,
                CitronSaveManager.formatSize(entry.sizeBytes),
                CitronSaveManager.formatDate(entry.lastModified),
            ),
            color = Color(0xFF93A6BC),
            fontSize = 12.sp,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onBackup, Modifier.weight(1f)) {
                Text(stringResource(R.string.retro_scr_save_backup))
            }
            OutlinedButton(onClick = onRestore, Modifier.weight(1f)) {
                Text(stringResource(R.string.retro_scr_save_restore))
            }
            OutlinedButton(onClick = onCopyPath, Modifier.weight(1f)) {
                Text(stringResource(R.string.retro_scr_save_copy_path))
            }
            Button(onClick = onDelete, Modifier.weight(1f)) {
                Text(stringResource(R.string.retro_scr_save_delete))
            }
        }
    }
}

@Composable
private fun RestoreDialog(
    titleId: String,
    context: Context,
    onDismiss: () -> Unit,
    onRestore: (backup: File) -> Unit,
) {
    val backups = remember { CitronSaveManager.listBackups(context, titleId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.retro_scr_save_restore_pick)) },
        text = {
            if (backups.isEmpty()) {
                Text(stringResource(R.string.retro_scr_save_no_backups))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    backups.forEach { b ->
                        OutlinedButton(onClick = { onRestore(b) }, Modifier.fillMaxWidth()) {
                            val nm = CitronSaveManager.readBackupName(b) ?: titleId
                            Text("$nm  (${b.nameWithoutExtension.substringAfter('_')})")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun copyPath(context: Context, path: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("save_path", path))
    Toast.makeText(context, R.string.retro_scr_save_path_copied, Toast.LENGTH_SHORT).show()
}

/** A save entry paired with its resolved display name (game name, or titleId as fallback). */
private data class SaveRow(
    val entry: CitronSaveManager.CitronSaveEntry,
    val name: String,
)

/** Loads all saves with their resolved display names (NAND + Download metadata; run off main). */
private fun loadSaveRows(context: Context): List<SaveRow> =
    CitronSaveManager.listSaves(context).map {
        SaveRow(it, CitronSaveManager.gameNameFor(context, it.titleId))
    }
