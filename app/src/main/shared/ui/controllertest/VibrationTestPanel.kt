package com.winlator.cmod.shared.ui.controllertest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.shared.theme.WinNativeAccent
import com.winlator.cmod.shared.theme.WinNativeTextPrimary
import com.winlator.cmod.shared.theme.WinNativeTextSecondary

private val VibrationLive = Color(0xFF57C777)

@Composable
fun VibrationTestPanel(
    snapshot: ControllerTestSnapshot?,
    vibrationRedirectEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val supported = snapshot?.hasVibrator
    val motorCount = snapshot?.vibratorCount ?: 0
    val canTest = supported == true && motorCount > 0

    Row(
        modifier
            .verticalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier
                .weight(1f)
                .testMenuCard()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            VibrationInfoRow(
                label = stringResource(R.string.controller_test_vibration_supported),
                value =
                    when (supported) {
                        true -> stringResource(R.string.controller_test_vibration_supported_yes)
                        false -> stringResource(R.string.controller_test_vibration_supported_no)
                        null -> "—"
                    },
                status =
                    when (supported) {
                        true -> VibrationLive
                        false -> WinNativeTextSecondary
                        null -> null
                    },
            )
            TestMenuDivider()
            VibrationInfoRow(
                label = stringResource(R.string.controller_test_vibration_motors),
                value =
                    if (supported == true && motorCount > 0) {
                        stringResource(R.string.controller_test_vibration_motors_count, motorCount)
                    } else {
                        "—"
                    },
            )
            TestMenuDivider()
            VibrationInfoRow(
                label = stringResource(R.string.controller_test_vibration_redirect),
                value =
                    if (vibrationRedirectEnabled) {
                        stringResource(R.string.controller_test_vibration_redirect_on)
                    } else {
                        stringResource(R.string.controller_test_vibration_redirect_off)
                    },
                status = if (vibrationRedirectEnabled) VibrationLive else WinNativeTextSecondary,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .testMenuCard()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                stringResource(R.string.controller_test_vibration_test_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = WinNativeTextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { ControllerTestBus.onRumble?.invoke(-1) },
                enabled = canTest,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WinNativeAccent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.controller_test_vibration_test_all), fontSize = 12.sp)
            }
            if (canTest) {
                Spacer(Modifier.height(8.dp))
                for (i in 0 until motorCount) {
                    OutlinedButton(
                        onClick = { ControllerTestBus.onRumble?.invoke(i) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.controller_test_vibration_test_motor, i + 1), fontSize = 12.sp)
                    }
                    if (i != motorCount - 1) {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun VibrationInfoRow(
    label: String,
    value: String,
    status: Color? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = WinNativeTextSecondary,
            modifier = Modifier.weight(1f),
        )
        if (status != null) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(status),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = WinNativeTextPrimary,
        )
    }
}