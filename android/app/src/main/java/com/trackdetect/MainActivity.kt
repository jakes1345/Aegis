package com.trackdetect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Ground = Color(0xFF0E1116)
private val Panel = Color(0xFF161B23)
private val Ink = Color(0xFFE6EAF1)
private val InkDim = Color(0xFFA8B2C1)
private val Muted = Color(0xFF6F7A8B)
private val Rule = Color(0xFF262E3A)
private val Accent = Color(0xFFFF7A3D)
private val Critical = Color(0xFFF2545B)
private val Caution = Color(0xFFE8B33D)
private val Clear = Color(0xFF3DB88A)

private val REQUIRED = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.POST_NOTIFICATIONS
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ground, surface = Panel)) {
                Surface(color = Ground, modifier = Modifier.fillMaxSize()) {
                    Dashboard(
                        onStart = { startService(Intent(this, ScanService::class.java)) },
                        onStop = {
                            startService(
                                Intent(this, ScanService::class.java)
                                    .apply { action = ScanService.ACTION_STOP }
                            )
                        },
                        hasPermissions = { REQUIRED.all { granted(it) } }
                    )
                }
            }
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun Dashboard(
    onStart: () -> Unit,
    onStop: () -> Unit,
    hasPermissions: () -> Boolean
) {
    val detections by Registry.detections.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()

    // Android 14 refuses to start a location-typed foreground service unless the
    // permission is already held, so the grant has to complete before the start.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onStart() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        Spacer(Modifier.height(24.dp))
        Text(
            "TRACK DETECT",
            color = Ink,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            "Nothing is called following until it has travelled 300 m with you.",
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(16.dp))
        StatusPanel(status, detections)

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (status.scanning) onStop()
                else if (hasPermissions()) onStart()
                else launcher.launch(REQUIRED)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (status.scanning) Rule else Accent,
                contentColor = if (status.scanning) Ink else Color(0xFF12161D)
            ),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (status.scanning) "STOP SCANNING" else "START SCANNING",
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        if (detections.isEmpty()) {
            EmptyState(status.scanning)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(detections, key = { it.key }) { DetectionRow(it) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun StatusPanel(status: ScanStatus, detections: List<Detection>) {
    val following = detections.count { it.following }
    val verdict = when {
        following > 0 -> "$following CONFIRMED FOLLOWING" to Critical
        detections.any { it.persistent } -> "Persistent devices, unconfirmed" to Caution
        else -> "Nothing confirmed" to Clear
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(4.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            verdict.first,
            color = verdict.second,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            if (status.scanning) "Scanning · ${detections.size} device(s) tracked"
            else "Idle",
            color = InkDim,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )

        Text(
            if (status.hasFix) {
                val move = if (status.moving) "moving" else "stationary"
                "Fix %.4f, %.4f · %s · %.1f km travelled".format(
                    status.lat ?: 0.0, status.lon ?: 0.0, move, status.travelledM / 1000.0
                )
            } else {
                "No position fix — devices can only be reported as persistent"
            },
            color = if (status.hasFix) Muted else Caution,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )

        status.error?.let {
            Text(it, color = Critical, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun EmptyState(scanning: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (scanning) "Nothing flagged yet." else "Not scanning.",
            color = InkDim,
            fontSize = 14.sp
        )
        if (scanning) {
            Text(
                "Consumer tags appear within seconds. Proving something follows you " +
                    "takes ten minutes of presence and a 300 m drive — leave it running " +
                    "and go somewhere.",
                color = Muted,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun DetectionRow(d: Detection) {
    val stripe = when {
        d.following -> Critical
        d.persistent -> Caution
        d.identified -> Accent
        else -> Rule
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(4.dp))
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(if (d.following) 116.dp else 96.dp)
                .background(stripe)
        )
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    d.name,
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    d.threat.name,
                    color = stripe,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }

            d.tracker?.let {
                Text("${it.label} · ${it.brand}", color = Accent, fontSize = 12.sp)
            }

            Text(
                buildString {
                    append("${d.rssi} dBm")
                    d.approxMetres?.let { append(" · ~%.1f m".format(it)) }
                    append(" · ${d.sightings}x")
                    if (d.places > 0) append(" · ${d.places} place(s)")
                    if (d.rotations > 0) append(" · ${d.rotations} MAC change(s)")
                },
                color = Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            val (line, colour) = when (d.confidence) {
                FollowConfidence.CONFIRMED ->
                    "FOLLOWING — heard ${d.displacementM.toInt()} m apart. On you or your vehicle." to Critical

                FollowConfidence.NO_POSITION ->
                    "Persistent — no GPS fix, so following cannot be confirmed." to Caution

                FollowConfidence.NOT_MOVED_ENOUGH ->
                    "Persistent — you have only moved ${d.displacementM.toInt()} m since first contact." to Caution

                FollowConfidence.NONE ->
                    (if (d.identified) "Known tracker type, present too briefly to mean anything."
                    else "Unidentified. Watching for a following pattern.") to Muted
            }

            Text(line, color = colour, fontSize = 12.sp)

            if (d.following) {
                Text(
                    "Search: wheel wells, bumper covers, OBD-II port, under seats, bag linings.",
                    color = InkDim,
                    fontSize = 11.sp
                )
            }
        }
    }
}
