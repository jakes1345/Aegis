package com.xat.aegis

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private const val ONBOARDING_PREFS = "prefs"
private const val ONBOARDING_DONE_KEY = "onboarding_done"
private const val TOTAL_PAGES = 4

// ── Helpers ────────────────────────────────────────────────────────────────────

fun isOnboardingDone(context: Context): Boolean =
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .getBoolean(ONBOARDING_DONE_KEY, false)

fun markOnboardingDone(context: Context) =
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(ONBOARDING_DONE_KEY, true).apply()

// ── Colour tokens local to this file ──────────────────────────────────────────

private val OGroundClr    = Color(0xFF0E1116)
private val OPanelClr     = Color(0xFF161B23)
private val OInkClr       = Color(0xFFE6EAF1)
private val OInkDimClr    = Color(0xFFA8B2C1)
private val OMutedClr     = Color(0xFF6F7A8B)
private val OAccentClr    = Color(0xFFFF7A3D)
private val OClearClr     = Color(0xFF3DB88A)
private val OCriticalClr  = Color(0xFFF2545B)
private val OBlueClr      = Color(0xFF4A8FD4)

// ── OnboardingScreen ───────────────────────────────────────────────────────────

@Composable
fun OnboardingScreen(startScanService: () -> Unit, onComplete: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pagerState = rememberPagerState(pageCount = { TOTAL_PAGES })
    val scope = rememberCoroutineScope()

    fun finish() {
        markOnboardingDone(context)
        startScanService()
        onComplete()
    }

    Box(Modifier.fillMaxSize().background(OGroundClr)) {
        // Pages
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> OPage1()
                1 -> OPage2()
                2 -> OPage3()
                else -> OPage4(onStart = { finish() })
            }
        }

        // Skip button — top right, hidden on last page
        if (pagerState.currentPage < TOTAL_PAGES - 1) {
            TextButton(
                onClick = { scope.launch { pagerState.scrollToPage(TOTAL_PAGES - 1) } },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 16.dp)
            ) {
                Text("SKIP", color = OMutedClr, fontSize = 12.sp, letterSpacing = 1.sp)
            }
        }

        // Page indicator dots + NEXT button — pinned at bottom
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Dot indicators
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(TOTAL_PAGES) { i ->
                    val active = pagerState.currentPage == i
                    Box(
                        Modifier
                            .size(if (active) 10.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) OAccentClr else OMutedClr.copy(alpha = 0.5f)
                            )
                    )
                }
            }

            // NEXT button — hidden on the last page (which has its own Start button)
            if (pagerState.currentPage < TOTAL_PAGES - 1) {
                Button(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OAccentClr,
                        contentColor = Color(0xFF12161D)
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.width(200.dp)
                ) {
                    Text("NEXT", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }
    }
}

// ── Page 1: App purpose ────────────────────────────────────────────────────────

@Composable
private fun OPage1() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Shield graphic
        Box(
            Modifier
                .size(120.dp)
                .background(OPanelClr, RoundedCornerShape(60.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = OAccentClr, fontSize = 44.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(36.dp))
        Text(
            "Aegis",
            color = OInkClr, fontSize = 30.sp, fontWeight = FontWeight.Black,
            letterSpacing = 1.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Counter-surveillance for professionals",
            color = OAccentClr, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Aegis continuously scans for hidden surveillance devices — " +
            "Bluetooth trackers, IMSI catchers (fake cell towers), rogue WiFi access " +
            "points, and NFC tags — so you can detect and document covert monitoring " +
            "in real time.",
            color = OInkDimClr, fontSize = 14.sp, textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Detection data stays on this device. The Map tab downloads map tiles from " +
            "OpenStreetMap, which sees your IP address and the area you're viewing.",
            color = OMutedClr, fontSize = 12.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(120.dp))
    }
}

// ── Page 2: Permissions ────────────────────────────────────────────────────────

@Composable
private fun OPage2() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .padding(top = 72.dp, bottom = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Permissions",
            color = OInkClr, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Three permissions are needed for full functionality:",
            color = OMutedClr, fontSize = 13.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        OPermissionItem(
            label = "Nearby Devices (Bluetooth)",
            desc = "Scans for BLE advertising packets from trackers " +
                   "(AirTags, Tile, Samsung SmartTag) in the vicinity."
        )
        Spacer(Modifier.height(10.dp))
        OPermissionItem(
            label = "Precise Location",
            desc = "Required to map where detected devices appear alongside you. " +
                   "Confirming that a tracker is following — not just nearby — " +
                   "requires GPS to prove shared movement across distance."
        )
        Spacer(Modifier.height(10.dp))
        OPermissionItem(
            label = "Notifications",
            desc = "Sends an alert the moment a device is confirmed as following, " +
                   "even when the app is in the background."
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "You will be prompted for permissions when you start scanning.",
            color = OMutedClr, fontSize = 11.sp, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun OPermissionItem(label: String, desc: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(OPanelClr, RoundedCornerShape(8.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(8.dp)
                .background(OAccentClr, CircleShape)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = OInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = OInkDimClr, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

// ── Page 3: How it works ───────────────────────────────────────────────────────

@Composable
private fun OPage3() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .padding(top = 72.dp, bottom = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "How It Works",
            color = OInkClr, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        OHowItWorksItem(
            color = OAccentClr,
            title = "BLE Tracker Detection",
            text = "Bluetooth trackers broadcast advertising packets continuously. Aegis " +
                   "matches known signatures (AirTag, Tile, SmartTag) and monitors whether a device " +
                   "reappears across multiple locations — confirming it is following rather than " +
                   "just passing by."
        )
        Spacer(Modifier.height(10.dp))
        OHowItWorksItem(
            color = OBlueClr,
            title = "IMSI Catcher Detection",
            text = "Aegis builds a local baseline of your usual cell towers, then watches " +
                   "for anomalies — forced technology downgrades, unknown cells at familiar " +
                   "locations, abnormally strong signals — that indicate a portable surveillance " +
                   "device impersonating a legitimate tower."
        )
        Spacer(Modifier.height(10.dp))
        OHowItWorksItem(
            color = OMutedClr,
            title = "WiFi & NFC",
            text = "Rogue access points mimic carrier networks to intercept traffic. NFC tags " +
                   "hidden in objects can track location. Aegis flags suspicious SSIDs " +
                   "and known covert-tracking NFC chip types in real time."
        )
    }
}

@Composable
private fun OHowItWorksItem(color: Color, title: String, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(OPanelClr, RoundedCornerShape(8.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(60.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = OInkDimClr, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

// ── Page 4: Ready ──────────────────────────────────────────────────────────────

@Composable
private fun OPage4(onStart: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(120.dp)
                .background(OPanelClr, RoundedCornerShape(60.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("✓", color = OClearClr, fontSize = 54.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(36.dp))
        Text(
            "Ready to Scan",
            color = OInkClr, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Aegis is ready. Start scanning now to begin monitoring for surveillance devices.",
            color = OInkDimClr, fontSize = 14.sp, textAlign = TextAlign.Center,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "For best results: keep the app running while travelling. Confirming a tracker " +
            "is following typically requires 10 minutes and 300 metres of shared movement.",
            color = OMutedClr, fontSize = 12.sp, textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(44.dp))
        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(
                containerColor = OAccentClr,
                contentColor = Color(0xFF12161D)
            ),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "START SCANNING",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = 1.sp
            )
        }
        Spacer(Modifier.height(140.dp))
    }
}
