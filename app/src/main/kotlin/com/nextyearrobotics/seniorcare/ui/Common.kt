package com.nextyearrobotics.seniorcare.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.nextyearrobotics.seniorcare.R
import com.nextyearrobotics.seniorcare.ui.theme.BrandError

const val BATTERY_LOW_PERCENT = 20

fun placeIcon(kind: String): ImageVector = when (kind) {
    "home" -> Icons.Default.Home
    "market" -> Icons.Default.Storefront
    "mart" -> Icons.Default.ShoppingCart
    "senior_center" -> Icons.Default.Groups
    "church" -> Icons.Default.Church
    "temple" -> Icons.Default.TempleBuddhist
    "hospital" -> Icons.Default.LocalHospital
    "pharmacy" -> Icons.Default.LocalPharmacy
    else -> Icons.Default.Place
}

/** 로봇 배터리. 로봇은 5 % 단위로 낸다(전압 추정이라 1 %는 거짓 정밀도). 색만으로 구분하지 않게 아이콘 모양도 바꾼다. */
@Composable
fun BatteryChip(percent: Int?, modifier: Modifier = Modifier) {
    val low = percent != null && percent <= BATTERY_LOW_PERCENT
    val icon = when {
        percent == null -> Icons.Default.BatteryUnknown
        low -> Icons.Default.BatteryAlert
        percent >= 90 -> Icons.Default.BatteryFull
        percent >= 70 -> Icons.Default.Battery6Bar
        percent >= 50 -> Icons.Default.Battery4Bar
        percent >= 35 -> Icons.Default.Battery3Bar
        else -> Icons.Default.Battery2Bar
    }
    val text = if (percent == null) stringResource(R.string.battery_unknown) else stringResource(R.string.battery_percent, percent)
    Surface(modifier.semantics(mergeDescendants = true) { contentDescription = text }, shape = MaterialTheme.shapes.large,
        color = if (low) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(28.dp), tint = if (low) BrandError else LocalContentColor.current)
            Text(if (percent == null) "—" else "$percent%", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun SosButton(busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = !busy, modifier = modifier.fillMaxWidth().heightIn(min = 72.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BrandError, contentColor = MaterialTheme.colorScheme.onError)) {
        Icon(Icons.Default.Sos, null, Modifier.size(36.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(if (busy) R.string.sos_sending else R.string.sos_button), style = MaterialTheme.typography.titleLarge)
            if (!busy) Text(stringResource(R.string.sos_button_sub), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 잘못 눌러 보호자에게 알림이 가지 않게 한 번 더 묻는다. */
@Composable
fun SosConfirmDialog(onSend: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Sos, null, Modifier.size(48.dp), tint = BrandError) },
        title = { Text(stringResource(R.string.sos_confirm_title), style = MaterialTheme.typography.headlineMedium) },
        text = { Text(stringResource(R.string.sos_confirm_body), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            Button(onClick = onSend, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandError, contentColor = MaterialTheme.colorScheme.onError)) {
                Text(stringResource(R.string.sos_confirm_send))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(stringResource(R.string.sos_confirm_cancel)) }
        })
}
