package com.winlator.cmod.shared.ui.controllertest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.runtime.input.controls.Binding
import com.winlator.cmod.runtime.input.controls.ControlsProfile
import com.winlator.cmod.runtime.input.controls.ExternalController
import com.winlator.cmod.shared.theme.WinNativeDanger
import com.winlator.cmod.shared.theme.WinNativeOutline
import com.winlator.cmod.shared.theme.WinNativeTextPrimary
import com.winlator.cmod.shared.theme.WinNativeTextSecondary

private val BindWarn = Color(0xFFE7B64C)
private val BindRowFill = Color(0xFF14141E)

@Composable
fun VisualControllerBinder(
    profile: ControlsProfile?,
    art: PadArt,
    snapshot: ControllerTestSnapshot?,
    controllerId: String?,
    controllerName: String?,
    allProfiles: List<ControlsProfile>,
    onSelectProfile: (ControlsProfile) -> Unit,
    onCreateProfile: (String) -> Unit,
    onRenameProfile: (() -> Unit)?,
    onSaved: () -> Unit,
    onOpenAllBindings: (() -> Unit)?,
    onSelectNone: (() -> Unit)? = null,
    onDeleteProfile: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var bindRev by remember { mutableIntStateOf(0) }
    val controller: ExternalController? =
        remember(profile, controllerId, bindRev) {
            profile?.let { resolveBindController(it, controllerId, controllerName) }
        }

    var selectedId by remember(profile) { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf(BindCategory.KEYBOARD) }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var profMenu by remember { mutableStateOf(false) }

    fun bumpSaved() {
        bindRev++
        onSaved()
    }

    val targets = bindTargets(controller)
    val boundN = remember(bindRev, controller) { boundCount(controller) }
    val labels =
        remember(bindRev, controller) {
            targets.associate { it.id to bindingLabelOrNull(controller, it.id) }
        }
    val notes =
        remember(bindRev, controller) {
            targets.associate { it.id to bindingNoteOrNull(controller, it.id) }
        }
    val boundBindIds = remember(bindRev, controller) { labels.filterValues { it != null }.keys.toSet() }

    var prevButtons by remember { mutableIntStateOf(0) }
    var lastDpadUp by remember { mutableStateOf(false) }
    var lastDpadDown by remember { mutableStateOf(false) }
    var lastDpadLeft by remember { mutableStateOf(false) }
    var lastDpadRight by remember { mutableStateOf(false) }
    var lastThumbLH by remember { mutableIntStateOf(0) }
    var lastThumbLV by remember { mutableIntStateOf(0) }
    var lastThumbRH by remember { mutableIntStateOf(0) }
    var lastThumbRV by remember { mutableIntStateOf(0) }
    var lastTriggerLDir by remember { mutableIntStateOf(0) }
    var lastTriggerRDir by remember { mutableIntStateOf(0) }
    var lastGuide by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ControllerTestBus.snapshot.collect { snap ->
            if (snap == null) {
                prevButtons = 0
                lastDpadUp = false; lastDpadDown = false; lastDpadLeft = false; lastDpadRight = false
                lastThumbLH = 0; lastThumbLV = 0; lastThumbRH = 0; lastThumbRV = 0
                lastTriggerLDir = 0; lastTriggerRDir = 0
                lastGuide = false
                return@collect
            }

            val newPresses = snap.buttons and prevButtons.inv()
            prevButtons = snap.buttons
            if (newPresses != 0) {
                val bit = Integer.lowestOneBit(newPresses)
                selectedId =
                    when (Integer.numberOfTrailingZeros(bit)) {
                        0 -> "a"
                        1 -> "b"
                        2 -> "x"
                        3 -> "y"
                        4 -> "lb"
                        5 -> "rb"
                        6 -> "back"
                        7 -> "start"
                        8 -> "l3"
                        9 -> "r3"
                        10 -> "lt"
                        11 -> "rt"
                        else -> null
                    }
                if (selectedId != null) return@collect
            }

            fun dpad(up: Boolean, down: Boolean, left: Boolean, right: Boolean): Boolean {
                if (up != lastDpadUp) { lastDpadUp = up; if (up) { selectedId = "dup"; return true } }
                if (down != lastDpadDown) { lastDpadDown = down; if (down) { selectedId = "ddown"; return true } }
                if (left != lastDpadLeft) { lastDpadLeft = left; if (left) { selectedId = "dleft"; return true } }
                if (right != lastDpadRight) { lastDpadRight = right; if (right) { selectedId = "dright"; return true } }
                return false
            }
            if (dpad(snap.dpadUp, snap.dpadDown, snap.dpadLeft, snap.dpadRight)) return@collect

            fun detectStick(
                x: Float, y: Float,
                lastH: Int, lastV: Int,
                leftId: String, rightId: String, upId: String, downId: String,
            ): String? {
                val thresh = 0.5f
                val h = when { x <= -thresh -> -1; x >= thresh -> 1; else -> 0 }
                val v = when { y <= -thresh -> -1; y >= thresh -> 1; else -> 0 }
                if (h != 0 && h != lastH) return if (h < 0) leftId else rightId
                if (v != 0 && v != lastV) return if (v < 0) upId else downId
                return null
            }

            val lx = snap.thumbLX; val ly = snap.thumbLY
            val lHit = detectStick(lx, ly, lastThumbLH, lastThumbLV, "lsl", "lsr", "lsu", "lsd")
            val lh = when { lx <= -0.5f -> -1; lx >= 0.5f -> 1; else -> 0 }
            val lv = when { ly <= -0.5f -> -1; ly >= 0.5f -> 1; else -> 0 }
            lastThumbLH = lh; lastThumbLV = lv
            if (lHit != null) { selectedId = lHit; return@collect }

            val rx = snap.thumbRX; val ry = snap.thumbRY
            val rHit = detectStick(rx, ry, lastThumbRH, lastThumbRV, "rsl", "rsr", "rsu", "rsd")
            val rh = when { rx <= -0.5f -> -1; rx >= 0.5f -> 1; else -> 0 }
            val rv = when { ry <= -0.5f -> -1; ry >= 0.5f -> 1; else -> 0 }
            lastThumbRH = rh; lastThumbRV = rv
            if (rHit != null) { selectedId = rHit; return@collect }

            fun trigDir(v: Float, last: Int, id: String): Int {
                val d = if (v >= 0.5f) 1 else 0
                if (d != 0 && d != last) { selectedId = id; return d }
                return d
            }
            val newTL = trigDir(snap.triggerL, lastTriggerLDir, "lt")
            lastTriggerLDir = newTL
            if (newTL != 0) return@collect
            val newTR = trigDir(snap.triggerR, lastTriggerRDir, "rt")
            lastTriggerRDir = newTR
            if (newTR != 0) return@collect

            if (snap.guide != lastGuide) {
                lastGuide = snap.guide
                if (snap.guide) selectedId = "guide"
            }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.controller_bind_profile),
                fontSize = 11.sp,
                color = WinNativeTextSecondary,
            )
            Spacer(Modifier.width(8.dp))
            Box {
                OutlinedButton(
                    onClick = { profMenu = true },
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.widthIn(max = 180.dp),
                ) {
                    Text(
                        profile?.name ?: stringResource(R.string.controller_bind_profile_none),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(
                    expanded = profMenu,
                    onDismissRequest = { profMenu = false },
                    modifier = Modifier.testMenuCard(),
                ) {
                    if (onSelectNone != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.controller_bind_profile_native)) },
                            onClick = {
                                profMenu = false
                                onSelectNone()
                            },
                        )
                        TestMenuDivider()
                    }
                    allProfiles.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name) },
                            onClick = {
                                profMenu = false
                                onSelectProfile(p)
                            },
                        )
                        TestMenuDivider()
                    }
                    if (profile != null) {
                        if (onRenameProfile != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.controller_bind_rename)) },
                                onClick = {
                                    profMenu = false
                                    onRenameProfile()
                                },
                            )
                        }
                        if (onDeleteProfile != null) {
                            TestMenuDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.controller_bind_delete),
                                        color = WinNativeDanger,
                                    )
                                },
                                onClick = {
                                    profMenu = false
                                    onDeleteProfile()
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { showNew = !showNew }) {
                Text(stringResource(R.string.controller_bind_new), fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            if (onOpenAllBindings != null) {
                TextButton(onClick = onOpenAllBindings) {
                    Text(stringResource(R.string.controller_bind_all_bindings), fontSize = 12.sp)
                }
            }
        }
        if (showNew) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(R.string.controller_bind_new_name), fontSize = 12.sp)
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onCreateProfile(newName.trim())
                            newName = ""
                            showNew = false
                        }
                    },
                    shape = RoundedCornerShape(9.dp),
                ) { Text(stringResource(R.string.controller_bind_create), fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (profile == null || controller == null) {
            Text(
                if (profile == null) {
                    stringResource(R.string.controller_bind_select_profile)
                } else {
                    stringResource(R.string.controller_bind_no_controller)
                },
                style = MaterialTheme.typography.bodySmall,
                color = WinNativeTextSecondary,
            )
            return@Column
        }

        if (boundN > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(BindWarn.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .border(1.dp, BindWarn.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.controller_bind_remap_on, boundN, targets.size),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BindWarn,
                    )
                    Text(
                        stringResource(R.string.controller_bind_remap_warning),
                        fontSize = 11.sp,
                        color = WinNativeTextSecondary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        fillNative(controller, profile)
                        bumpSaved()
                    },
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) { Text(stringResource(R.string.controller_bind_fill_native), fontSize = 11.sp) }
            }
            Spacer(Modifier.height(8.dp))
        }

        val padBox: @Composable () -> Unit = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PadArtView(
                    art = art,
                    snapshot = snapshot,
                    boundBindIds = boundBindIds,
                    selectedBindId = selectedId,
                    onTapBind = { id -> selectedId = id },
                )
            }
        }
        val panel: @Composable () -> Unit = {
            val current = selectedId
            if (current == null) {
                BindSummary(labels, boundN, targets) { id -> selectedId = id }
            } else {
                BindEditor(
                    id = current,
                    currentLabel = labels[current],
                    note = notes[current] ?: "",
                    category = category,
                    onCategory = { category = it },
                    onNoteChange = { note ->
                        setBindingNote(controller, current, note)
                    },
                    onNoteCommit = {
                        profile?.save()
                        bumpSaved()
                    },
                    onPick = { bnd ->
                        setTarget(controller, profile, current, bnd)
                        bumpSaved()
                    },
                    onNative = {
                        val nat = BIND_BY_ID[current]?.native
                        if (nat != null) {
                            setTarget(controller, profile, current, nat)
                            bumpSaved()
                        }
                    },
                    onClear = {
                        clearTarget(controller, profile, current)
                        bumpSaved()
                    },
                    onDone = { selectedId = null },
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 520.dp) {
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { padBox() }
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) { panel() }
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    padBox()
                    Spacer(Modifier.height(12.dp))
                    panel()
                }
            }
        }
    }
}

@Composable
private fun BindSummary(
    labels: Map<String, String?>,
    boundN: Int,
    targets: List<BindTarget>,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.controller_bind_summary, boundN, targets.size),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = WinNativeTextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        targets.forEach { t ->
            val label = labels[t.id]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .background(BindRowFill, RoundedCornerShape(9.dp))
                        .clickable { onSelect(t.id) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    bindTargetName(context, t),
                    fontSize = 12.sp,
                    color = WinNativeTextPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("→", fontSize = 12.sp, color = WinNativeTextSecondary)
                Spacer(Modifier.width(8.dp))
                Text(
                    label ?: stringResource(R.string.controller_bind_native),
                    fontSize = 11.sp,
                    color = if (label != null) BIND_BLUE else WinNativeTextSecondary,
                    fontWeight = if (label != null) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun BindEditor(
    id: String,
    currentLabel: String?,
    note: String,
    category: BindCategory,
    onCategory: (BindCategory) -> Unit,
    onNoteChange: (String) -> Unit,
    onNoteCommit: () -> Unit,
    onPick: (Binding) -> Unit,
    onNative: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val t = BIND_BY_ID[id]
    val targetName = t?.let { bindTargetName(context, it) } ?: id
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.controller_bind_label),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = WinNativeTextPrimary,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                targetName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = BIND_BLUE,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.common_ui_done), fontSize = 12.sp)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    R.string.controller_bind_current,
                    currentLabel ?: stringResource(R.string.controller_bind_current_native),
                ),
                fontSize = 12.sp,
                color = if (currentLabel != null) BIND_BLUE else WinNativeTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            Spacer(Modifier.width(6.dp))
            var showNoteDialog by remember { mutableStateOf(false) }
            var dialogNote by remember(note) { mutableStateOf(note) }
            val hasNote = note.isNotEmpty()
            OutlinedButton(
                onClick = { showNoteDialog = true },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    if (hasNote) note else stringResource(R.string.controller_bind_note),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
            }
            if (showNoteDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showNoteDialog = false
                    },
                    title = {
                        Text(stringResource(R.string.controller_bind_note), fontSize = 14.sp)
                    },
                    text = {
                        OutlinedTextField(
                            value = dialogNote,
                            onValueChange = { dialogNote = it },
                            singleLine = true,
                            placeholder = {
                                Text(stringResource(R.string.controller_bind_note), fontSize = 13.sp)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onNoteChange(dialogNote)
                                onNoteCommit()
                                showNoteDialog = false
                            },
                        ) {
                            Text(stringResource(R.string.common_ui_done), fontSize = 13.sp)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNoteDialog = false }) {
                            Text(stringResource(R.string.common_ui_cancel), fontSize = 13.sp)
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            BindCategory.entries.forEach { cat ->
                val on = cat == category
                Box(
                    Modifier
                        .background(
                            if (on) BIND_BLUE.copy(alpha = 0.2f) else BindRowFill,
                            RoundedCornerShape(8.dp),
                        )
                        .border(1.dp, if (on) BIND_BLUE else WinNativeOutline, RoundedCornerShape(8.dp))
                        .clickable { onCategory(cat) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        when (cat) {
                            BindCategory.KEYBOARD -> stringResource(R.string.controller_bind_category_keyboard)
                            BindCategory.MOUSE -> stringResource(R.string.controller_bind_category_mouse)
                            BindCategory.XBOX -> stringResource(R.string.controller_bind_category_xbox)
                            BindCategory.NONE -> stringResource(R.string.controller_bind_category_none)
                        },
                        fontSize = 11.sp,
                        color = if (on) WinNativeTextPrimary else WinNativeTextSecondary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        val optionLabels = categoryLabels(category)
        val optionValues = categoryValues(category)
        val n = minOf(optionLabels.size, optionValues.size)
        val perRow = 4
        var i = 0
        while (i < n) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            ) {
                var col = 0
                while (col < perRow && i < n) {
                    val idx = i
                    OutlinedButton(
                        onClick = { onPick(optionValues[idx]) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            optionLabels[idx],
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    i++
                    col++
                }
                while (col < perRow) {
                    Spacer(Modifier.weight(1f))
                    col++
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (t?.native != null) {
                OutlinedButton(
                    onClick = onNative,
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        stringResource(R.string.controller_bind_native_button, targetName),
                        fontSize = 11.sp,
                    )
                }
            }
            Button(
                onClick = onClear,
                shape = RoundedCornerShape(9.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE2564E).copy(alpha = 0.85f),
                    ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(stringResource(R.string.controller_bind_clear), fontSize = 11.sp) }
        }
    }
}