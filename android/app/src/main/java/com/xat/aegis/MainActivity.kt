package com.xat.aegis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.core.view.WindowCompat
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.viewinterop.AndroidView
import com.xat.aegis.CatcherFinding
import com.xat.aegis.analysis.CardVault
import com.xat.aegis.analysis.NfcScanner
import com.xat.aegis.analysis.PhoneHealthMonitor
import com.xat.aegis.analysis.Report
import com.xat.aegis.comms.CallManager
import com.xat.aegis.comms.CallPhase
import com.xat.aegis.comms.CommsNotifications
import com.xat.aegis.comms.CommsRepository
import android.nfc.cardemulation.CardEmulation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import android.content.ComponentName
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

// ── Colour tokens ───────────────────────────────────────────────────────

private val Ground     = Color(0xFF0E1116)
private val Panel      = Color(0xFF161B23)
private val PanelLight = Color(0xFF1D2430)
private val MapGround  = Color(0xFF0A0E13)
private val Ink        = Color(0xFFE6EAF1)
private val InkDim     = Color(0xFFA8B2C1)
private val Muted      = Color(0xFF6F7A8B)
private val Rule       = Color(0xFF262E3A)
private val Accent     = Color(0xFFFF7A3D)
private val Critical   = Color(0xFFF2545B)
private val Caution    = Color(0xFFE8B33D)
private val Clear      = Color(0xFF3DB88A)
private val Blue       = Color(0xFF4A8FD4)

private val CardShape = RoundedCornerShape(4.dp)

/**
 * Everything the app asks for up front.
 *
 * Fine and coarse location are always requested together: from Android 12 a
 * request naming ACCESS_FINE_LOCATION without ACCESS_COARSE_LOCATION is ignored
 * outright, so the location prompt never appeared and the scanner could not start.
 */
private val REQUIRED = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.POST_NOTIFICATIONS
)

/**
 * The subset without which scanning cannot run at all: the scan itself, and the
 * location permission that a location-typed foreground service is refused without.
 * Coarse location rides along because Android requires it in the same request;
 * a grant of fine location always includes it, so checking both is checking fine.
 *
 * Notifications are separate on purpose. Requiring them meant declining the
 * notification prompt silently blocked the whole detector.
 */
private val ESSENTIAL = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

/** The tab indices MainApp lays out, for code outside it that needs to name one. */
private const val TAB_NFC = 4
private const val TAB_DEVICE = 6
private const val TAB_COMMS = CommsNotifications.COMMS_TAB_INDEX

// ── Activity ───────────────────────────────────────────────────────────

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var cardEmulation: CardEmulation? = null

    // Constructing the vault touches nothing; the Keystore is only reached inside
    // its methods, which never throw — so no keystore state can take down launch.
    private val cardVault by lazy { CardVault(applicationContext) }
    private val hceComponent by lazy { ComponentName(this, CardEmulationService::class.java) }

    /**
     * The static device-health scan (accessibility services, device admins, debug
     * settings) needs no service, so the Activity runs it too — otherwise the Device
     * tab read "no issues" with the scanner off and nothing having looked.
     */
    private val phoneHealthMonitor by lazy { PhoneHealthMonitor(applicationContext) }

    /** Whether reader mode is currently enabled on the adapter by this activity. */
    private var readerModeOn = false

    /**
     * Answering from the notification lands here; the microphone is asked for
     * if the call would be the first time. Declined means the call is declined.
     */
    private val microphoneForAccept = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) CallManager.accept() else CallManager.reject()
    }

    /**
     * True between onResume and onPause. The lifecycle's own state cannot be used
     * for this from inside onResume: androidx only marks the Activity RESUMED after
     * onResume returns, so a guard on it there always failed and reader mode was
     * never switched on.
     */
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AppSettings.load(this)
        Registry.bindTrustStore(this)
        nfcAdapter = runCatching { NfcAdapter.getDefaultAdapter(this) }.getOrNull()
        cardEmulation = nfcAdapter?.let { runCatching { CardEmulation.getInstance(it) }.getOrNull() }

        // Emulation state lives only in this process, so if nothing is armed now,
        // any AIDs still registered belong to a previous process — they survive
        // reboots — and would keep routing readers to the service. Drop them.
        if (Registry.emulating.value == null) clearHceAids()

        // Show the stored timeline even before the scanner has run this session.
        lifecycleScope.launch(Dispatchers.IO) { runCatching { TimelineLog.publish(applicationContext) } }

        // Results of BiometricPrompt arrive through the Registry, so that whichever
        // Activity instance is on screen when they land carries the operation out.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Registry.vaultAuth.collect { pending ->
                    if (pending != null) Registry.takeVaultAuth()?.let { handleAuthResult(it) }
                }
            }
        }

        // A tag tapped while Aegis was not in reader mode (backgrounded, or picked
        // from the system's NFC chooser) arrives as the launching intent.
        handleNfcIntent(intent)

        CommsRepository.init(applicationContext)
        CommsNotifications.ensureChannels(this)
        handleOpenThreadIntent(intent)

        val onboardingAlreadyDone = isOnboardingDone(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ground, surface = Panel)) {
                Surface(color = Ground, modifier = Modifier.fillMaxSize()) {
                    var onboardingDone by remember { mutableStateOf(onboardingAlreadyDone) }

                    val onboardingPermissions = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { grants ->
                        if (ESSENTIAL.all { grants[it] == true }) startScanning()
                    }

                    if (!onboardingDone) {
                        OnboardingScreen(
                            // Onboarding used to start the service straight off the
                            // last page without ever asking for a permission, so the
                            // service failed startForeground for want of location
                            // access and stopped itself. Tapping "Start" appeared to
                            // do nothing at all on a fresh install.
                            startScanService = {
                                val missing = REQUIRED.filterNot { granted(it) }
                                if (missing.isEmpty()) startScanning()
                                else onboardingPermissions.launch(missing.toTypedArray())
                            },
                            onComplete = { onboardingDone = true }
                        )
                    } else {
                        MainApp(
                            onStart = { startScanning() },
                            onStop = { sendToService(ScanService.ACTION_STOP) },
                            onClearData = { sendToService(ScanService.ACTION_CLEAR) },
                            hasPermissions = { ESSENTIAL.all { granted(it) } },
                            onAddToVault = { tag, label, replace -> addToVault(tag, label, replace) },
                            onRemoveVault = { id -> removeFromVault(id) },
                            onEmulate = { card -> if (card == null) stopEmulation() else armEmulation(card) },
                            onUnlockVault = { unlockVault() },
                            onLockVault = { Registry.lockVault() },
                            onEraseVault = { eraseVault() },
                            onRefreshDeviceHealth = { refreshDeviceHealth() }
                        )
                    }
                }
            }
        }
    }

    /**
     * Starts the scanner as a foreground service. Plain startService() is refused
     * whenever the app is not already foreground, which made restarting from a
     * notification or a cold intent unreliable.
     */
    private fun startScanning() {
        ContextCompat.startForegroundService(this, Intent(this, ScanService::class.java))
    }

    /**
     * Control messages (stop, clear) use plain startService: the activity is by
     * definition in the foreground when the user taps those, and startForegroundService
     * would oblige the service to promote itself even when it is only being asked to
     * clear data and shut down again.
     */
    private fun sendToService(serviceAction: String) {
        val intent = Intent(this, ScanService::class.java)
        intent.action = serviceAction
        startService(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        applyNfcMode()
        refreshDeviceHealth()
        // Envelopes that arrived while the app was closed are pulled on every
        // return to the foreground; the push endpoint is renewed if it was lost.
        CommsRepository.registerPushIfPossible()
        CommsRepository.syncInBackground()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
        nfcAdapter?.let { adapter ->
            if (readerModeOn) runCatching { adapter.disableReaderMode(this) }
        }
        readerModeOn = false
        runCatching { cardEmulation?.unsetPreferredService(this) }
    }

    /** The activity is singleTop, so a tag delivered while it is up lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
        handleOpenThreadIntent(intent)
    }

    /** A message notification asks for the COMMS tab and the conversation it is about. */
    private fun handleOpenThreadIntent(intent: Intent?) {
        if (intent == null || !intent.hasExtra(CommsNotifications.EXTRA_TAB)) return
        val tab = intent.getIntExtra(CommsNotifications.EXTRA_TAB, -1)
        val peer = intent.getStringExtra(CommsNotifications.EXTRA_PEER)
        intent.removeExtra(CommsNotifications.EXTRA_TAB)
        intent.removeExtra(CommsNotifications.EXTRA_PEER)
        val acceptCall = intent.getBooleanExtra(CommsNotifications.EXTRA_ACCEPT_CALL, false)
        intent.removeExtra(CommsNotifications.EXTRA_ACCEPT_CALL)
        if (tab < 0) return
        Registry.requestTab(tab)
        if (peer != null) Registry.requestThread(peer)
        if (acceptCall) {
            if (granted(Manifest.permission.RECORD_AUDIO)) CallManager.accept()
            else microphoneForAccept.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * The manifest registers for NDEF, TECH and TAG discovery, which puts Aegis in
     * the system's NFC chooser and delivers tags tapped while it is backgrounded.
     * Those intents were never read, so choosing Aegis dropped the tag on the floor.
     */
    private fun handleNfcIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED
        ) return
        val tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return
        // Consumed: a later onCreate (recreation) must not scan the same tag again.
        intent.action = null
        Registry.requestTab(TAB_NFC)
        // Parsing talks to the tag over the air and must not block the main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            val parsed = try { NfcScanner.parse(tag) } catch (_: Exception) { return@launch }
            Registry.addNfc(parsed)
            runCatching { recordNfc(parsed) }
        }
    }

    /** Runs the static findings scan off the main thread and publishes the result. */
    private fun refreshDeviceHealth() {
        lifecycleScope.launch(Dispatchers.IO) { runCatching { phoneHealthMonitor.scanAndPublish() } }
    }

    override fun onStop() {
        super.onStop()
        // Decrypted cards never outlive the app being on screen. Emulation, once
        // armed, keeps running — it is used with Aegis in the background — and the
        // banner and STOP work from the armed card's id and label alone.
        Registry.lockVault()
    }

    // ── NFC: reader mode versus card emulation ───────────────────────────────

    /**
     * Reader mode and card emulation are mutually exclusive: while reader mode is on,
     * the controller polls for tags and never answers a reader as a card. It used to
     * be switched on unconditionally in onResume, so emulation could not work at all
     * while Aegis was on screen. Now, while a card is armed, reader mode stays off
     * and Aegis is made the preferred HCE service for as long as it is in front.
     */
    private fun applyNfcMode() {
        // Reader mode is only valid on a resumed, live activity; the flag is kept by
        // hand because the lifecycle is still STARTED while onResume runs.
        if (!resumed || isFinishing) return
        val adapter = nfcAdapter ?: return
        if (Registry.emulating.value != null) {
            if (readerModeOn) runCatching { adapter.disableReaderMode(this) }
            readerModeOn = false
            runCatching { cardEmulation?.setPreferredService(this, hceComponent) }
        } else {
            runCatching { cardEmulation?.unsetPreferredService(this) }
            if (!readerModeOn) {
                readerModeOn = runCatching {
                    adapter.enableReaderMode(
                        this, readerCallback,
                        NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                        null
                    )
                }.isSuccess
            }
        }
    }

    /** Runs on an NFC binder thread, so the timeline's disk write is fine here. */
    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        val parsed = try { NfcScanner.parse(tag) } catch (_: Exception) { return@ReaderCallback }
        Registry.addNfc(parsed)
        runCatching { recordNfc(parsed) }
    }

    private fun recordNfc(t: NfcTag) {
        val now = System.currentTimeMillis()
        val status = Registry.status.value
        val detail = buildString {
            append("UID ").append(t.uid)
            if (t.atqa != null && t.sak != null) append(" · ATQA ${t.atqa} SAK ${t.sak}")
            if (t.skimmerFlags.isNotEmpty()) append(" — flags: ").append(t.skimmerFlags.joinToString("; "))
            else if (t.suspicious) append(" — ").append(t.note)
        }
        TimelineLog.record(
            applicationContext,
            TimelineEvent(
                id = "nfc@${t.uid}@$now",
                kind = EventKind.NFC_TAG,
                ts = now,
                title = if (t.suspicious) "Suspicious NFC tag: ${t.type}" else "NFC tag scanned: ${t.type}",
                detail = detail,
                severity = if (t.suspicious) Severity.HIGH else Severity.LOW,
                lat = status.lat, lon = status.lon
            ),
            dedupeKey = "nfc@${t.uid}"
        )
    }

    // ── Card emulation ───────────────────────────────────────────────────────

    private fun armEmulation(card: VaultCard) {
        // Emulating replays the card's stored credential, so it needs the vault open
        // — the card has to come from an unlocked vault, not a stale UI row.
        val state = Registry.vault.value
        if (state !is VaultState.Unlocked || state.cards.none { it.id == card.id }) {
            unlockVault()
            return
        }
        val emulation = cardEmulation ?: run {
            toast("This phone does not support NFC card emulation")
            return
        }
        val aids = card.apduPairs.mapNotNull { (cmd, _) ->
            val bytes = cmd.replace(" ", "")
            if (bytes.length >= 10 && (
                bytes.startsWith("00A40400", ignoreCase = true) ||
                bytes.startsWith("00A40404", ignoreCase = true))) {
                val len = bytes.substring(8, 10).toIntOrNull(16) ?: 0
                bytes.substring(10, minOf(10 + len * 2, bytes.length))
            } else null
        }.distinct().ifEmpty { listOf("F000000000") }

        val loaded = runCatching { CardEmulationService.loadPairs(card.apduPairs) }.isSuccess
        val registered = loaded && runCatching {
            emulation.registerAidsForService(hceComponent, CardEmulation.CATEGORY_OTHER, aids)
        }.getOrDefault(false)
        if (!registered) {
            CardEmulationService.activePairs = emptyList()
            clearHceAids()
            // The pairs and AIDs are gone, so whatever was armed before — switching
            // from card A to card B that then failed — is gone with them. Say so,
            // or the banner keeps announcing A with nothing behind it.
            Registry.setEmulating(null)
            applyNfcMode()
            toast("Android refused this card's application IDs — it cannot be emulated")
            return
        }
        Registry.setEmulating(ArmedCard(card.id, card.label))
        applyNfcMode()
    }

    private fun stopEmulation() {
        CardEmulationService.activePairs = emptyList()
        clearHceAids()
        Registry.setEmulating(null)
        applyNfcMode()
    }

    /** Removes the dynamically registered AIDs, which otherwise persist across reboots. */
    private fun clearHceAids() {
        runCatching { cardEmulation?.removeAidsForService(hceComponent, CardEmulation.CATEGORY_OTHER) }
    }

    // ── Vault ────────────────────────────────────────────────────────────────

    /**
     * Decrypted cards are only ever shown to an Activity that is on screen. onStop
     * re-locks the vault, and a decryption or save still in flight at that moment
     * used to publish Unlocked(cards) straight after it, leaving the cards readable
     * from the background. Anything that would publish Unlocked goes through here.
     */
    private fun publishUnlocked(cards: List<VaultCard>) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Registry.publishVault(VaultState.Unlocked(cards))
        }
        // Otherwise onStop has locked it; the cards are discarded with the coroutine.
    }

    /** Authenticates, then decrypts and publishes the vault. */
    private fun unlockVault(attempt: Int = 0) {
        Registry.publishVault(VaultState.Unlocking)
        authenticate(VaultAuthPurpose.Unlock(attempt))
    }

    private fun loadVault(attempt: Int) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { cardVault.load() }
            when (result) {
                is CardVault.LoadResult.Loaded -> publishUnlocked(result.cards)
                is CardVault.LoadResult.Failed ->
                    if (result.failure is CardVault.Failure.AuthRequired && attempt == 0) {
                        // The auth did not reach the key (it can lapse between the prompt
                        // and the decrypt). One re-prompt — but only while the Activity
                        // is in front to show it; otherwise "Unlocking" would sit there
                        // forever with no prompt behind it.
                        if (resumed) unlockVault(attempt + 1) else Registry.publishVault(VaultState.Locked)
                    } else {
                        Registry.publishVault(failedState(result.failure))
                    }
            }
        }
    }

    private fun addToVault(tag: NfcTag, label: String, replace: Boolean) {
        val card = VaultCard(
            id        = UUID.randomUUID().toString(),
            uid       = tag.uid,
            label     = label,
            profile   = tag.profile,
            addedTs   = System.currentTimeMillis(),
            apduPairs = tag.apduPairs
        )
        Registry.setReplacePrompt(null)
        writeVault(VaultOp.Add(card, replace))
    }

    private fun removeFromVault(id: String) {
        writeVault(VaultOp.Remove(id))
    }

    /**
     * Runs a vault write. The key's authentication lasts 30 seconds, so a write some
     * time after unlocking needs the user again: on AuthRequired this prompts and
     * retries once. Any other failure leaves the stored vault untouched and says why.
     *
     * The operation is data ([VaultOp]) rather than a closure so the retry can be
     * carried out by whichever Activity instance receives the prompt's result.
     */
    private fun writeVault(op: VaultOp, attempt: Int = 0) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (op) {
                    is VaultOp.Add -> cardVault.add(op.card, op.replace)
                    is VaultOp.Remove -> cardVault.remove(op.id)
                }
            }
            when (result) {
                is CardVault.WriteResult.Saved -> {
                    // Always publish the fresh list: + VAULT is only offered while the
                    // vault is open, and a save that went through the prompt has just
                    // proved the user's identity. Either way the tag row's IN VAULT
                    // state comes from this list, so it has to be current.
                    publishUnlocked(result.cards)
                    when (op) {
                        is VaultOp.Add -> toast("Saved \"${op.card.label}\" to the vault")
                        is VaultOp.Remove -> if (Registry.emulating.value?.id == op.id) stopEmulation()
                    }
                }
                is CardVault.WriteResult.Failed -> when (val f = result.failure) {
                    CardVault.Failure.AuthRequired ->
                        if (attempt == 0 && resumed) {
                            authenticate(VaultAuthPurpose.Write(op, attempt + 1))
                        } else {
                            toast("Vault not changed — authentication did not take effect")
                        }
                    CardVault.Failure.NoLockScreen -> toast(NO_LOCK_SCREEN)
                    is CardVault.Failure.Duplicate -> {
                        // Only an Add can be refused this way; the UI asks before
                        // overwriting, and the answer comes back with replace = true.
                        val add = op as? VaultOp.Add ?: return@launch
                        val tag = Registry.nfc.value.firstOrNull { it.uid == add.card.uid }
                        if (tag != null) {
                            Registry.setReplacePrompt(ReplacePrompt(tag, add.card.label, f.existing))
                        } else {
                            toast("\"${f.existing.label}\" is already in the vault with this UID")
                        }
                    }
                    else -> {
                        toast("Vault not changed — it could not be read")
                        Registry.publishVault(failedState(f))
                    }
                }
            }
        }
    }

    /**
     * Deletes an unreadable vault at the user's explicit request (behind a confirm
     * dialog). Confirms identity first when the phone can, so a borrowed, unlocked
     * phone cannot be used to wipe it.
     */
    private fun eraseVault() {
        if (BiometricManager.from(this).canAuthenticate(VAULT_AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            authenticate(VaultAuthPurpose.Erase)
        } else {
            doErase()
        }
    }

    private fun doErase() {
        lifecycleScope.launch {
            val erased = withContext(Dispatchers.IO) { cardVault.erase() }
            if (erased) {
                if (Registry.emulating.value != null) stopEmulation()
                publishUnlocked(emptyList())
                toast("Vault erased")
            } else {
                toast("The vault could not be erased")
            }
        }
    }

    /** Carries out the operation a completed BiometricPrompt was shown for. */
    private fun handleAuthResult(result: VaultAuthResult) {
        val purpose = result.purpose
        when (val outcome = result.outcome) {
            VaultAuthOutcome.Succeeded -> when (purpose) {
                is VaultAuthPurpose.Unlock -> loadVault(purpose.attempt)
                is VaultAuthPurpose.Write -> writeVault(purpose.op, purpose.attempt)
                VaultAuthPurpose.Erase -> doErase()
            }
            is VaultAuthOutcome.Failed -> when (purpose) {
                is VaultAuthPurpose.Unlock -> Registry.publishVault(
                    if (outcome.message == null) VaultState.Locked
                    else VaultState.Failed(outcome.message, retryable = true, erasable = false)
                )
                is VaultAuthPurpose.Write ->
                    toast(outcome.message ?: "Vault not changed — authentication cancelled")
                VaultAuthPurpose.Erase -> outcome.message?.let { toast(it) }
            }
        }
    }

    private fun failedState(f: CardVault.Failure): VaultState.Failed = when (f) {
        CardVault.Failure.AuthRequired -> VaultState.Failed(
            "Authentication did not reach the vault key. Try unlocking again.",
            retryable = true, erasable = false
        )
        CardVault.Failure.KeyInvalidated -> VaultState.Failed(
            "Android has permanently invalidated the vault key — this happens when the screen " +
                "lock is removed or reset. The stored cards can no longer be decrypted by anyone. " +
                "Nothing has been deleted; erase the vault to start a new one.",
            retryable = false, erasable = true
        )
        CardVault.Failure.NoLockScreen -> VaultState.Failed(
            NO_LOCK_SCREEN, retryable = true, erasable = false
        )
        // A refused overwrite is answered with a prompt, never a failed vault; this
        // only exists to keep the mapping total.
        is CardVault.Failure.Duplicate -> VaultState.Failed(
            "\"${f.existing.label}\" is already stored with this UID.",
            retryable = true, erasable = false
        )
        is CardVault.Failure.Error -> VaultState.Failed(
            "The vault could not be decrypted (${f.cause.javaClass.simpleName}). " +
                "Nothing has been changed or overwritten.",
            retryable = true, erasable = true
        )
    }

    /**
     * Shows BiometricPrompt allowing a strong biometric or the device PIN, pattern or
     * password — the same set the vault key accepts. The result is published to the
     * Registry as a [VaultAuthResult] for [purpose], not handed to a closure: the
     * callback then holds nothing of this Activity instance, and the instance alive
     * when the result lands (this one, or its replacement after a recreation) acts
     * on it.
     */
    private fun authenticate(purpose: VaultAuthPurpose) {
        fun fail(message: String?) =
            Registry.publishVaultAuth(VaultAuthResult(purpose, VaultAuthOutcome.Failed(message)))

        when (val can = BiometricManager.from(this).canAuthenticate(VAULT_AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> { fail(NO_LOCK_SCREEN); return }
            else -> {
                fail("This phone cannot confirm your identity right now (code $can).")
                return
            }
        }
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Registry.publishVaultAuth(VaultAuthResult(purpose, VaultAuthOutcome.Succeeded))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    Registry.publishVaultAuth(
                        VaultAuthResult(purpose, VaultAuthOutcome.Failed(if (cancelled) null else errString.toString()))
                    )
                }
                // onAuthenticationFailed is a single rejected attempt; the prompt
                // stays up for another try, so there is nothing to do.
            }
        )
        // Each operation says what it is: the erase and save prompts used to wear
        // the unlock's "view and use stored cards" copy.
        val (title, subtitle) = when (purpose) {
            is VaultAuthPurpose.Unlock -> "Unlock card vault" to "Confirm it's you to view and use stored cards"
            VaultAuthPurpose.Erase -> "Erase card vault" to "This permanently deletes every stored card"
            is VaultAuthPurpose.Write -> when (purpose.op) {
                is VaultOp.Add -> "Save card to vault" to "Confirm it's you to store this card"
                is VaultOp.Remove -> "Remove card from vault" to "Confirm it's you to change stored cards"
            }
        }
        // No negative button: with DEVICE_CREDENTIAL allowed the prompt supplies its
        // own "use PIN" path, and BiometricPrompt rejects a negative button then.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(VAULT_AUTHENTICATORS)
            .build()
        runCatching { prompt.authenticate(info) }
            .onFailure { fail("Could not show the ${title.lowercase(Locale.US)} prompt: ${it.message}") }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val VAULT_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        const val NO_LOCK_SCREEN =
            "Set a screen lock (PIN, pattern or password) in Android settings to use the vault."
    }
}

// ── Formatting helpers ──────────────────────────────────────────────────

private val fullFmt    = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)
private val clockFmt   = SimpleDateFormat("HH:mm:ss", Locale.US)
private val dayFmt     = SimpleDateFormat("MMM d", Locale.US)
private val dayYearFmt = SimpleDateFormat("MMM d yyyy", Locale.US)

private fun fmtTime(ts: Long): String = fullFmt.format(Date(ts))
private fun fmtClock(ts: Long): String = clockFmt.format(Date(ts))

private fun fmtAgo(now: Long, ts: Long): String {
    val s = ((now - ts) / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ${(s % 3600) / 60} min ago"
        else -> "${s / 86_400} d ago"
    }
}

private fun fmtDuration(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

private fun fmtMetres(m: Double): String = when {
    m < 10 -> "~%.1f m".format(Locale.US, m)
    m < 1000 -> "~%.0f m".format(Locale.US, m)
    else -> "~%.1f km".format(Locale.US, m / 1000.0)
}

private fun fmtDbm(rssi: Int): String = when {
    rssi == 0 -> "— dBm"
    rssi < 0 -> "−${-rssi} dBm"
    else -> "$rssi dBm"
}

private fun plural(n: Int, word: String) = if (n == 1) "$n $word" else "$n ${word}s"

private fun compass(bearing: Double): String {
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[((bearing + 22.5) / 45.0).toInt() % 8]
}

private fun dayLabel(ts: Long, now: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis
    val thisYear = cal.get(Calendar.YEAR)
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterdayStart = cal.timeInMillis
    if (ts >= todayStart) return "TODAY"
    if (ts >= yesterdayStart) return "YESTERDAY"
    cal.timeInMillis = ts
    val fmt = if (cal.get(Calendar.YEAR) == thisYear) dayFmt else dayYearFmt
    return fmt.format(Date(ts)).uppercase(Locale.US)
}

// ── Threat colouring ─────────────────────────────────────────────────────

private fun threatColor(t: Threat): Color = when (t) {
    Threat.CRITICAL -> Critical
    Threat.HIGH -> Accent
    Threat.MEDIUM -> Caution
    Threat.LOW -> Muted
    Threat.NONE -> Clear
}

private fun severityColor(s: Severity): Color = when (s) {
    Severity.CRITICAL -> Critical
    Severity.HIGH -> Accent
    Severity.MEDIUM -> Caution
    Severity.LOW -> Muted
}

/** Green at 0, yellow at 0.5, red at 1. */
private fun meterColor(t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return if (f <= 0.5f) lerp(Clear, Caution, f * 2f) else lerp(Caution, Critical, (f - 0.5f) * 2f)
}

private fun trailColor(t: Threat, following: Boolean): Color = when {
    following || t == Threat.CRITICAL -> Critical
    t == Threat.HIGH -> Accent
    t == Threat.MEDIUM -> Caution
    else -> Blue
}

// ── Shared building blocks ────────────────────────────────────────────────

/** Wall-clock that ticks every [periodMs] so relative times stay honest. */
@Composable
private fun rememberNow(periodMs: Long = 1000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** Opacity that breathes 0.3 → 0.8 → 0.3, one second per leg. */
@Composable
private fun rememberPulse(): State<Float> {
    var high by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            high = !high
        }
    }
    return animateFloatAsState(
        targetValue = if (high) 0.8f else 0.3f,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "pulse"
    )
}

@Composable
private fun ThreatBadge(label: String, color: Color) {
    Text(
        label, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun SectionLabel(text: String, color: Color = Muted) {
    Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
}

@Composable
private fun KvRow(label: String, value: String, valueColor: Color = InkDim) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
        Text(
            value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NoticeBanner(color: Color, tag: String, text: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(color.copy(alpha = 0.12f), CardShape)
            .border(1.dp, color.copy(alpha = 0.5f), CardShape)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(tag, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(text, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PanelBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Panel, CardShape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

// ── Threat explainer target ─────────────────────────────────────────────────

private sealed class ExplainerTarget {
    data class BleDevice(val detection: Detection) : ExplainerTarget()
    data class CellIndicator(val finding: CatcherFinding) : ExplainerTarget()
}

// ── Root composable ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainApp(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearData: () -> Unit,
    hasPermissions: () -> Boolean,
    onAddToVault: (NfcTag, String, Boolean) -> Unit,
    onRemoveVault: (String) -> Unit,
    onEmulate: (VaultCard?) -> Unit,
    onUnlockVault: () -> Unit,
    onLockVault: () -> Unit,
    onEraseVault: () -> Unit,
    onRefreshDeviceHealth: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("SCAN", "MAP", "LOG", "CELL", "NFC", "WIFI", "DEVICE", "COMMS")

    val detections by Registry.detections.collectAsStateWithLifecycle()
    val trusted by Registry.trusted.collectAsStateWithLifecycle()
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val nfc by Registry.nfc.collectAsStateWithLifecycle()
    val wifi by Registry.wifi.collectAsStateWithLifecycle()
    val phoneHealth by Registry.phoneHealth.collectAsStateWithLifecycle()
    val unreadMessages by CommsRepository.unread.collectAsStateWithLifecycle()
    val activeCall by CallManager.call.collectAsStateWithLifecycle()

    // A call ringing or in progress takes the COMMS tab regardless of where the
    // user was: the notification lands there, and so does the in-app case.
    LaunchedEffect(activeCall?.id, activeCall?.phase) {
        if (activeCall != null && activeCall?.phase != CallPhase.ENDED) tab = TAB_COMMS
    }

    // A tag delivered by a system NFC intent lands on the NFC tab; a message
    // notification lands on the COMMS tab.
    val tabRequest by Registry.tabRequest.collectAsStateWithLifecycle()
    LaunchedEffect(tabRequest) {
        if (tabRequest != null) Registry.takeTabRequest()?.let { tab = it.coerceIn(0, tabs.size - 1) }
    }
    val threadRequest by Registry.threadRequest.collectAsStateWithLifecycle()

    // Settings navigation and threat explainer state
    var showSettings by remember { mutableStateOf(false) }
    var explainerTarget by remember { mutableStateOf<ExplainerTarget?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false }, onClearData = onClearData)
        return
    }

    // One red dot per tab that currently holds something worth looking at.
    val followingLive = detections.any { it.following && it.address !in trusted }
    val alerts = listOf(
        followingLive,
        followingLive && detections.any { it.following && it.points.isNotEmpty() },
        false,
        cell.available && cell.level.ordinal >= Threat.HIGH.ordinal,
        nfc.any { it.suspicious },
        wifi.isNotEmpty(),
        phoneHealth.level.ordinal >= Threat.HIGH.ordinal,
        unreadMessages > 0
    )

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).statusBarsPadding()) {
            when (tab) {
                0 -> ScanScreen(
                    onStart, onStop, hasPermissions,
                    onSettingsClick = { showSettings = true },
                    onShowExplainer = { d -> explainerTarget = ExplainerTarget.BleDevice(d) }
                )
                1 -> MapScreen()
                2 -> TimelineScreen()
                3 -> CellScreen(
                    onShowExplainer = { f -> explainerTarget = ExplainerTarget.CellIndicator(f) }
                )
                4 -> NfcScreen(
                    onAddToVault = onAddToVault,
                    onRemoveVault = onRemoveVault,
                    onEmulate = onEmulate,
                    onUnlockVault = onUnlockVault,
                    onLockVault = onLockVault,
                    onEraseVault = onEraseVault
                )
                5 -> WifiScreen()
                TAB_DEVICE -> DeviceScreen(onShown = onRefreshDeviceHealth)
                TAB_COMMS -> CommsScreen(
                    openPeer = threadRequest,
                    onPeerConsumed = { Registry.takeThreadRequest() }
                )
            }
        }
        NavigationBar(
            containerColor = Panel,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding()
        ) {
            tabs.forEachIndexed { i, label ->
                NavigationBarItem(
                    selected = tab == i,
                    onClick = { tab = i },
                    icon = {
                        Box {
                            TabIcon(index = i, selected = tab == i)
                            if (alerts[i]) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(Critical, CircleShape)
                                        .align(Alignment.TopEnd)
                                        .offset(x = 2.dp, y = (-2).dp)
                                )
                            }
                        }
                    },
                    label = {
                        Text(label, fontSize = 10.sp, letterSpacing = 0.3.sp, maxLines = 1)
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent,
                        selectedTextColor = Accent,
                        indicatorColor = Accent.copy(alpha = 0.15f),
                        unselectedIconColor = Muted,
                        unselectedTextColor = Muted
                    )
                )
            }
        }
    }

    // Threat explainer bottom sheet
    val target = explainerTarget
    if (target != null) {
        ModalBottomSheet(
            onDismissRequest = { explainerTarget = null },
            sheetState = sheetState,
            containerColor = Panel
        ) {
            ThreatExplainerSheet(target = target, onDismiss = { explainerTarget = null })
        }
    }
}

// ── Tab icons ──────────────────────────────────────────────────────────

@Composable
private fun TabIcon(index: Int, selected: Boolean) {
    val color = if (selected) Accent else Muted
    Canvas(Modifier.size(22.dp)) {
        val cx = center.x
        val cy = center.y
        when (index) {
            0 -> { // SCAN — radar rings
                drawCircle(color.copy(alpha = 0.35f), radius = size.minDimension * 0.46f, center = center, style = Stroke(1f))
                drawCircle(color.copy(alpha = 0.65f), radius = size.minDimension * 0.3f, center = center, style = Stroke(1.5f))
                drawCircle(color, radius = size.minDimension * 0.14f, center = center)
            }
            1 -> { // MAP — location pin
                val pinR = size.minDimension * 0.3f
                val pinTop = Offset(cx, cy - pinR * 1.2f)
                val path = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(cx - pinR, cy - pinR * 2.2f, cx + pinR, cy))
                    moveTo(cx, cy)
                    lineTo(cx - pinR * 0.5f, cy + pinR * 0.6f)
                    lineTo(cx + pinR * 0.5f, cy + pinR * 0.6f)
                    close()
                }
                drawPath(path, color)
                drawCircle(Panel, radius = pinR * 0.38f, center = Offset(cx, cy - pinR))
            }
            2 -> { // LOG — stacked lines
                val w = size.width * 0.75f
                val gaps = listOf(0.25f, 0.5f, 0.75f)
                val widths = listOf(w, w * 0.78f, w * 0.56f)
                gaps.zip(widths).forEach { (frac, lineW) ->
                    drawLine(color, Offset(cx - lineW / 2f, size.height * frac), Offset(cx + lineW / 2f, size.height * frac), strokeWidth = 2f)
                }
            }
            3 -> { // CELL — signal bars
                val barW = size.width * 0.13f
                val gap = size.width * 0.07f
                val totalW = barW * 4 + gap * 3
                val startX = cx - totalW / 2f
                for (b in 0..3) {
                    val barH = size.height * (0.25f + b * 0.18f)
                    val x = startX + b * (barW + gap)
                    val barColor = if (selected || b < 2) color else color.copy(alpha = 0.3f)
                    drawRect(barColor, topLeft = Offset(x, size.height - barH - 2f), size = Size(barW, barH))
                }
            }
            4 -> { // NFC — near-field arcs
                val arcSizes = listOf(0.85f, 0.55f, 0.3f)
                arcSizes.forEachIndexed { i, scale ->
                    val r = size.minDimension * scale * 0.5f
                    drawArc(
                        color = color.copy(alpha = 1f - i * 0.3f),
                        startAngle = 210f, sweepAngle = 120f,
                        useCenter = false,
                        topLeft = Offset(cx - r, cy - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(2f - i * 0.4f)
                    )
                }
                drawCircle(color, radius = 2.5f, center = Offset(cx - size.minDimension * 0.35f, cy))
            }
            5 -> { // WIFI — wifi arcs
                val arcSizes = listOf(0.9f, 0.6f, 0.3f)
                arcSizes.forEachIndexed { i, scale ->
                    val r = size.minDimension * scale * 0.5f
                    drawArc(
                        color = color.copy(alpha = if (selected || i > 0) 1f else 0.5f),
                        startAngle = 200f, sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(cx - r, cy - r * 0.5f),
                        size = Size(r * 2, r * 2),
                        style = Stroke(if (i == 0) 2f else 1.5f)
                    )
                }
                drawCircle(color, radius = 2.5f, center = Offset(cx, size.height * 0.8f))
            }
            7 -> { // COMMS — speech bubble
                val w = size.width * 0.8f
                val h = size.height * 0.6f
                val left = cx - w / 2f
                val top = cy - h / 2f - size.height * 0.05f
                val path = Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            androidx.compose.ui.geometry.Rect(left, top, left + w, top + h),
                            androidx.compose.ui.geometry.CornerRadius(h * 0.3f)
                        )
                    )
                    moveTo(left + w * 0.25f, top + h)
                    lineTo(left + w * 0.18f, top + h + size.height * 0.2f)
                    lineTo(left + w * 0.45f, top + h)
                    close()
                }
                drawPath(path, color.copy(alpha = 0.2f))
                drawPath(path, color, style = Stroke(1.5f))
                for (i in 0..2) {
                    drawCircle(color, radius = 1.6f, center = Offset(left + w * (0.3f + i * 0.2f), top + h / 2f))
                }
            }
            else -> { // DEVICE — shield
                val sw = size.width * 0.76f
                val sh = size.height * 0.88f
                val ox = cx - sw / 2f
                val oy = cy - sh / 2f + size.height * 0.04f
                val shieldPath = Path().apply {
                    moveTo(cx, oy)
                    lineTo(ox + sw, oy + sh * 0.25f)
                    lineTo(ox + sw, oy + sh * 0.6f)
                    cubicTo(ox + sw, oy + sh * 0.85f, cx, oy + sh, cx, oy + sh)
                    cubicTo(cx, oy + sh, ox, oy + sh * 0.85f, ox, oy + sh * 0.6f)
                    lineTo(ox, oy + sh * 0.25f)
                    close()
                }
                drawPath(shieldPath, color.copy(alpha = 0.2f))
                drawPath(shieldPath, color, style = Stroke(1.5f))
                // check mark inside
                drawLine(color, Offset(cx - sw * 0.22f, cy + sh * 0.05f), Offset(cx - sw * 0.05f, cy + sh * 0.22f), strokeWidth = 1.8f)
                drawLine(color, Offset(cx - sw * 0.05f, cy + sh * 0.22f), Offset(cx + sw * 0.25f, cy - sh * 0.1f), strokeWidth = 1.8f)
            }
        }
    }
}

// ── Scan (BLE) screen ────────────────────────────────────────────────────

@Composable
private fun ScanScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    hasPermissions: () -> Boolean,
    onSettingsClick: () -> Unit = {},
    onShowExplainer: (Detection) -> Unit = {}
) {
    val detections by Registry.detections.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val trusted by Registry.trusted.collectAsStateWithLifecycle()
    val timeline by Registry.timeline.collectAsStateWithLifecycle()
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val nfc by Registry.nfc.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val now = rememberNow()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        // Start as soon as the scan can actually run. Notifications being declined
        // costs the alert, not the detector.
        if (ESSENTIAL.all { grants[it] == true }) onStart()
    }

    // Devices the user has vouched for drop out of the threat picture and sink to the bottom.
    val live = remember(detections, trusted) { detections.filter { it.address !in trusted } }
    val ordered = remember(detections, trusted) { detections.sortedBy { it.address in trusted } }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "§header") {
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("AEGIS", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val i = Report.share(context, status, detections, timeline, cell, nfc)
                        context.startActivity(Intent.createChooser(i, "Share evidence report"))
                    }) { Text("EXPORT", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp) }
                    TextButton(onClick = onSettingsClick) {
                        Text("⚙", color = Muted, fontSize = 18.sp)
                    }
                }
            }
        }

        if (status.scanning) {
            item(key = "§meter") { ThreatMeter(live, cell) }
        }

        item(key = "§status") { ScanStatusPanel(status, detections, trusted) }

        item(key = "§toggle") {
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
                shape = CardShape, modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (status.scanning) "STOP SCANNING" else "START SCANNING",
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
        }

        if (detections.isEmpty()) {
            item(key = "§empty") { EmptyState(status.scanning) }
        } else {
            item(key = "§list-label") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("FLAGGED DEVICES")
                    Text(
                        "${live.size} live · ${detections.size - live.size} trusted · tap a card for detail",
                        color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }
            items(ordered, key = { it.key }) { d ->
                DetectionRow(
                    d = d,
                    trusted = d.address in trusted,
                    now = now,
                    onShowExplainer = if (d.threat == Threat.CRITICAL || d.threat == Threat.HIGH)
                        { { onShowExplainer(d) } } else null
                )
            }
        }

        item(key = "§footer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ThreatMeter(live: List<Detection>, cell: CellStatus) {
    val bleScore = live.maxOfOrNull { it.score } ?: 0
    val cellScore = if (cell.available) cell.score else 0
    val score = maxOf(bleScore, cellScore).coerceIn(0, 100)
    val fraction by animateFloatAsState(
        targetValue = score / 100f,
        animationSpec = tween(durationMillis = 600),
        label = "threat"
    )
    val color = meterColor(fraction)

    val following = live.count { it.following }
    val known = live.count { it.identified }
    val cellHot = cell.available && cell.level.ordinal >= Threat.HIGH.ordinal

    val (summary, summaryColor) = when {
        cellHot && (following > 0 || known > 0) ->
            "IMSI anomaly + BLE tracker: possible coordinated surveillance" to Critical
        following == 1 -> "CRITICAL: device following you" to Critical
        following > 1 -> "CRITICAL: $following devices following you" to Critical
        cellHot -> "IMSI anomaly: possible fake cell tower in range" to Critical
        known > 0 -> "${plural(known, "known tracker")} in range" to Accent
        live.isNotEmpty() -> "${plural(live.size, "unidentified device")} under observation" to Caution
        else -> "No threats" to Clear
    }

    Column(
        Modifier.fillMaxWidth().background(Panel, CardShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("THREAT LEVEL")
            Text(
                "$score", color = color, fontSize = 16.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
            )
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(Rule, RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color, RoundedCornerShape(4.dp)))
        }
        Text(summary, color = summaryColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "BLE peak $bleScore · cellular $cellScore · scale 0–100",
            color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ScanStatusPanel(status: ScanStatus, detections: List<Detection>, trusted: Set<String>) {
    val live = detections.filter { it.address !in trusted }
    val following = live.count { it.following }
    val (verdict, verdictColor) = when {
        following > 0 -> "$following CONFIRMED FOLLOWING" to Critical
        live.any { it.persistent } -> "Persistent devices, unconfirmed" to Caution
        else -> "Nothing confirmed" to Clear
    }
    Column(
        Modifier.fillMaxWidth().background(Panel, CardShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(verdict, color = verdictColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val scanText = when {
            !status.scanning -> "Idle"
            status.nearbyCount > detections.size ->
                "Scanning · ${detections.size} flagged · ${status.nearbyCount} total in range"
            else -> "Scanning · ${detections.size} device(s) flagged"
        }
        Text(scanText, color = InkDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        val filtered = status.nearbyCount - detections.size
        if (status.scanning && filtered > 0) {
            Text("+$filtered transient signals filtered", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Text(if (status.hasFix)
            "Fix %.4f, %.4f · ${if (status.moving) "moving" else "stationary"} · %.1f km".format(
                Locale.US, status.lat ?: 0.0, status.lon ?: 0.0, status.travelledM / 1000.0)
            else "No fix — following cannot be confirmed without GPS",
            color = if (status.hasFix) Muted else Caution, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (trusted.isNotEmpty()) {
            Text("${plural(trusted.size, "device")} marked safe and excluded from the threat level",
                color = Muted, fontSize = 11.sp)
        }
        status.error?.let { Text(it, color = Critical, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
private fun EmptyState(scanning: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (scanning) "Nothing flagged yet." else "Not scanning.", color = InkDim, fontSize = 14.sp)
        if (scanning) Text("Proving something follows you takes 10 min presence and a 300 m drive. Leave it running.",
            color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun RssiBar(rssi: Int) {
    val strength = if (rssi == 0) 0f else ((rssi + 100) / 60f).coerceIn(0f, 1f)
    val color = when {
        rssi == 0 -> Muted
        rssi >= -60 -> Critical
        rssi >= -75 -> Accent
        rssi >= -90 -> Caution
        else -> Muted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(48.dp).height(5.dp).background(Rule, RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth(strength).fillMaxHeight().background(color, RoundedCornerShape(2.dp)))
        }
        Text(fmtDbm(rssi), color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DetectionRow(
    d: Detection,
    trusted: Boolean,
    now: Long,
    onShowExplainer: (() -> Unit)? = null
) {
    var expanded by remember(d.key) { mutableStateOf(false) }
    val stripe = if (trusted) Rule else threatColor(d.threat)
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "chevron"
    )

    Row(
        Modifier.fillMaxWidth()
            .clip(CardShape)
            .background(Panel)
            .clickable { expanded = !expanded }
            .height(IntrinsicSize.Min)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(stripe))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    d.name, color = if (trusted) InkDim else Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (trusted) ThreatBadge("TRUSTED", Muted) else ThreatBadge(d.threat.name, threatColor(d.threat))
                Text("›", color = Muted, fontSize = 18.sp, modifier = Modifier.rotate(chevron))
            }

            val t = d.tracker
            if (t != null) {
                Text("${t.label} · ${t.brand}", color = if (trusted) Muted else Accent, fontSize = 12.sp)
            } else {
                Text("Unidentified BLE device", color = Muted, fontSize = 12.sp)
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${d.sightings}× · last ${fmtClock(d.lastSeen)} · ${fmtAgo(now, d.lastSeen)}",
                    color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                RssiBar(d.rssi)
            }

            if (d.following && !trusted) {
                Text(
                    "⚠ Confirmed following — ${d.displacementM.roundToInt()} m apart",
                    color = Critical, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
            }

            if (expanded) DetectionDetail(d, trusted, onShowExplainer)
        }
    }
}

@Composable
private fun DetectionDetail(d: Detection, trusted: Boolean, onShowExplainer: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Hairline()

        // ── Tracker type ──
        SectionLabel("TRACKER TYPE")
        val t = d.tracker
        if (t != null) {
            Column(
                Modifier.fillMaxWidth().background(PanelLight, CardShape).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(t.label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    ThreatBadge(t.threat.name, threatColor(t.threat))
                }
                Text("Brand: ${t.brand} · Threat rating: ${t.threat.name}", color = InkDim, fontSize = 12.sp)
                Text(t.notes, color = InkDim, fontSize = 12.sp, lineHeight = 17.sp)
            }
        } else {
            Text(
                "No known tracker signature matched. Flagged on behaviour alone — persistence, movement and signal pattern.",
                color = Muted, fontSize = 12.sp, lineHeight = 17.sp
            )
        }

        // ── Observation ──
        SectionLabel("OBSERVATION")
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            KvRow("First seen", fmtTime(d.firstSeen))
            KvRow("Last seen", fmtTime(d.lastSeen))
            KvRow("Present for", fmtDuration(d.lastSeen - d.firstSeen))
            KvRow(
                "Sightings",
                if (d.places > 0) "${d.sightings} across ${plural(d.places, "distinct location")}"
                else "${d.sightings} (no location data)"
            )
            KvRow("Est. distance", d.approxMetres?.let { fmtMetres(it) } ?: "unknown")
            KvRow("Signal", fmtDbm(d.rssi))
            KvRow("Displacement", "Observed ${d.displacementM.roundToInt()} metres apart")
            KvRow("Address", d.address)
            KvRow(
                "MAC rotation",
                if (d.rotations > 0) "MAC rotated ${d.rotations}× · ${plural(d.addresses, "address").replace("addresss", "addresses")} seen"
                else "Stable address",
                if (d.rotations > 0) Caution else InkDim
            )
            KvRow("Threat score", "${d.score}/100", if (trusted) Muted else threatColor(d.threat))
        }

        // ── Follow assessment ──
        SectionLabel("FOLLOW ASSESSMENT")
        val (line, lineColor) = when (d.confidence) {
            FollowConfidence.NONE -> "Not enough data to assess" to Muted
            FollowConfidence.NO_POSITION -> "No GPS — cannot confirm movement" to Caution
            FollowConfidence.NOT_MOVED_ENOUGH -> "Present but not following" to Caution
            FollowConfidence.CONFIRMED -> "⚠ Confirmed following" to Critical
        }
        Text(line, color = if (trusted) Muted else lineColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val explanation = when (d.confidence) {
            FollowConfidence.NONE ->
                if (d.identified) "Known tracker type, but present too briefly to establish a pattern."
                else "Watching for a persistence pattern."
            FollowConfidence.NO_POSITION ->
                "Device is persistent. A GPS fix is required to prove it moves with you."
            FollowConfidence.NOT_MOVED_ENOUGH ->
                "Persistent, but only ${d.displacementM.roundToInt()} m of shared travel so far. Confirmation needs about 300 m."
            FollowConfidence.CONFIRMED ->
                "Seen ${d.displacementM.roundToInt()} m apart while moving with you. On you or your vehicle. " +
                    "Check: wheel wells, bumper covers, OBD-II port, under seats, bag linings."
        }
        Text(explanation, color = InkDim, fontSize = 12.sp, lineHeight = 17.sp)

        // ── Actions ──
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (trusted) {
                TextButton(
                    onClick = { Registry.untrust(d.address) },
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(1.dp, Rule)
                ) { Text("TRUSTED ✓", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp) }
                Text("Tap to revoke and put it back in the threat picture.", color = Muted, fontSize = 10.sp,
                    modifier = Modifier.weight(1f))
            } else {
                TextButton(
                    onClick = { Registry.trust(d.address) },
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(1.dp, Clear.copy(alpha = 0.6f))
                ) { Text("MARK SAFE", color = Clear, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
                Text("Marks this device as yours and removes it from the threat level.", color = Muted, fontSize = 10.sp,
                    modifier = Modifier.weight(1f))
            }
        }

        // "What is this?" explainer button for high/critical threats
        if (onShowExplainer != null && !trusted) {
            TextButton(
                onClick = onShowExplainer,
                shape = RoundedCornerShape(3.dp),
                border = BorderStroke(1.dp, Accent.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "WHAT IS THIS? HOW DO I RESPOND?",
                    color = Accent, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }
        }
    }
}

// ── Map screen ─────────────────────────────────────────────────────────

private data class MarkerKey(val argbFill: Int, val sizeDp: Int, val outline: Boolean)

/**
 * Marker icons by (colour, size, outline). The map redraws every 1.5 s and used to
 * allocate a fresh bitmap for every marker each time; there are only a handful of
 * distinct icons, so they are drawn once and shared.
 *
 * Touched only from the main thread (composition and effects).
 */
private val markerCache = HashMap<MarkerKey, BitmapDrawable>()

private fun markerBitmap(argbFill: Int, sizeDp: Int, outline: Boolean = false): BitmapDrawable =
    markerCache.getOrPut(MarkerKey(argbFill, sizeDp, outline)) {
        drawMarkerBitmap(argbFill, sizeDp, outline)
    }

private fun drawMarkerBitmap(argbFill: Int, sizeDp: Int, outline: Boolean): BitmapDrawable {
    val px = (sizeDp * Resources.getSystem().displayMetrics.density + 0.5f).toInt()
    val bm = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bm)
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = argbFill
        style = AndroidPaint.Style.FILL
    }
    canvas.drawCircle(px / 2f, px / 2f, px / 2f - 1f, paint)
    if (outline) {
        paint.color = android.graphics.Color.WHITE
        paint.style = AndroidPaint.Style.STROKE
        paint.strokeWidth = px * 0.18f
        canvas.drawCircle(px / 2f, px / 2f, px / 2f - 2f, paint)
    }
    return BitmapDrawable(Resources.getSystem(), bm)
}

private fun threatArgb(threat: Threat, following: Boolean): Int = when {
    following -> 0xFFF2545B.toInt()
    threat.ordinal >= Threat.HIGH.ordinal -> 0xFFFF7A3D.toInt()
    threat.ordinal >= Threat.MEDIUM.ordinal -> 0xFFE8B33D.toInt()
    else -> 0xFF4A8FD4.toInt()
}

@Composable
private fun MapScreen() {
    val mapData by Registry.map.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val cellHot = cell.available && cell.level.ordinal >= Threat.HIGH.ordinal
    val followingTrails = mapData.devices.count { it.following }

    val mapView = remember {
        OsmConfig.getInstance().load(
            context,
            context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE)
        )
        OsmConfig.getInstance().userAgentValue = "Aegis/${BuildConfig.VERSION_NAME}"
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(17.0)
            isFlingEnabled = true
        }
    }

    // Only center the map on the very first GPS fix; after that the user controls it.
    var hasCentered by remember { mutableStateOf(false) }

    LaunchedEffect(status.lat, status.lon, mapData) {
        val lat = status.lat ?: return@LaunchedEffect
        val lon = status.lon ?: return@LaunchedEffect

        // Rebuild all overlays except the tile layer.
        mapView.overlays.removeAll { it !is org.osmdroid.views.overlay.TilesOverlay }

        // GPS track
        if (mapData.track.size > 1) {
            val line = Polyline(mapView).apply {
                outlinePaint.color = 0xFF4A8FD4.toInt()
                outlinePaint.strokeWidth = 8f
                outlinePaint.alpha = 180
                setPoints(mapData.track.map { GeoPoint(it.lat, it.lon) })
            }
            mapView.overlays.add(0, line)
        }

        // Device markers
        for (trail in mapData.devices) {
            val pt = trail.points.lastOrNull() ?: continue
            val argb = threatArgb(trail.threat, trail.following)
            val sizeDp = if (trail.following) 22 else 16
            val marker = Marker(mapView).apply {
                position = GeoPoint(pt.lat, pt.lon)
                title = trail.name
                snippet = "%.6f, %.6f".format(pt.lat, pt.lon)
                icon = markerBitmap(argb, sizeDp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(marker)
        }

        // Cell anomaly markers (larger with outline)
        for (cm in mapData.cells) {
            val argb = threatArgb(cm.level, false)
            val marker = Marker(mapView).apply {
                position = GeoPoint(cm.point.lat, cm.point.lon)
                title = cm.label
                snippet = "%.6f, %.6f".format(cm.point.lat, cm.point.lon)
                icon = markerBitmap(argb, 20, outline = true)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(marker)
        }

        // Current position (blue dot with white ring)
        val posMarker = Marker(mapView).apply {
            position = GeoPoint(lat, lon)
            title = "Your position"
            snippet = "%.6f, %.6f".format(lat, lon)
            icon = markerBitmap(0xFF4A8FD4.toInt(), 20, outline = true)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        mapView.overlays.add(posMarker)

        if (!hasCentered) {
            mapView.controller.setCenter(GeoPoint(lat, lon))
            hasCentered = true
        }
        mapView.invalidate()
    }

    // The MapView follows the Activity's lifecycle, not just composition: it used to
    // be resumed once when the tab appeared and paused only when the tab was left,
    // so backgrounding Aegis on the Map tab left its tile threads and animations
    // running. Registering the observer on an already-resumed lifecycle replays
    // ON_RESUME, which is what brings the view up on first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            // Leaving the tab discards this MapView (it lives in remember{}), so it has
            // to release its tile provider threads and caches, or every visit to the
            // Map tab leaked one. Overlays are dropped from the list first: detaching
            // a Marker recycles its icon, and the icons are shared via markerCache.
            // Plain removal from the list does not detach, so they survive for reuse.
            mapView.overlays.removeAll { it !is org.osmdroid.views.overlay.TilesOverlay }
            mapView.onDetach()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Top-left status chip
        val chipColor = if (followingTrails > 0) Critical else Muted
        Text(
            buildString {
                append(if (status.hasFix) "GPS fix" else "No fix")
                if (status.lat != null) append("  %.5f, %.5f".format(status.lat, status.lon))
                if (mapData.devices.isNotEmpty()) append("  ·  ${plural(mapData.devices.size, "device")}")
                if (followingTrails > 0) append("  ·  $followingTrails FOLLOWING")
            },
            color = chipColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Panel.copy(alpha = 0.92f), CardShape)
                .padding(horizontal = 8.dp, vertical = 5.dp)
        )

        if (cellHot) {
            Text(
                "⚠ Cell anomaly — ${cell.level.name}",
                color = Caution, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Panel.copy(alpha = 0.92f), CardShape)
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }

        if (followingTrails > 0) {
            Text(
                "● ${plural(followingTrails, "device")} FOLLOWING YOU",
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .background(Critical.copy(alpha = 0.92f), CardShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

// ── Timeline screen ──────────────────────────────────────────────────────

@Composable
private fun TimelineScreen() {
    val events by Registry.timeline.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val now = rememberNow(60_000L)
    // Log is newest-first, so groups come out TODAY, YESTERDAY, then older days.
    val groups = remember(events, now / 60_000L) { events.groupBy { dayLabel(it.ts, now) } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("EVENT LOG", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(
            if (events.isEmpty()) "Nothing recorded" else "${plural(events.size, "recorded event")} · tap an event for location context",
            color = Muted, fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        if (events.isEmpty()) {
            Text("No events recorded yet. Start scanning to begin monitoring.", color = Muted, fontSize = 13.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { (label, list) ->
                    item(key = "§day-$label") { DayHeader(label, list.size) }
                    items(list, key = { it.id }) { TimelineRow(it, status) }
                }
                item(key = "§footer") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SectionLabel(label, Accent)
        Text(plural(count, "event"), color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TimelineRow(e: TimelineEvent, status: ScanStatus) {
    var open by remember(e.id) { mutableStateOf(false) }
    val col = severityColor(e.severity)
    val titleColor = if (e.severity == Severity.LOW) InkDim else col
    val lat = e.lat
    val lon = e.lon
    val hasLoc = lat != null && lon != null

    Row(
        Modifier.fillMaxWidth()
            .clip(CardShape)
            .background(Panel)
            .clickable { open = !open }
            .height(IntrinsicSize.Min)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(col))
        Column(
            Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(10.dp).background(col, CircleShape))
                Text(
                    e.title, color = titleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis
                )
                Text(fmtClock(e.ts), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (e.detail.isNotBlank()) {
                Text(
                    e.detail, color = Muted, fontSize = 12.sp,
                    maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis
                )
            }
            if (hasLoc) {
                Text("%.5f, %.5f".format(Locale.US, lat, lon), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (open) {
                Hairline()
                Text(
                    "${fmtTime(e.ts)} · ${e.kind.name.replace('_', ' ').lowercase(Locale.US)} · ${e.severity.name.lowercase(Locale.US)}",
                    color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
                if (lat != null && lon != null) {
                    EventLocationSnapshot(lat, lon, status)
                } else {
                    Text("No location recorded for this event", color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

/** A 100 dp square showing where the event happened relative to where the phone is now. */
@Composable
private fun EventLocationSnapshot(lat: Double, lon: Double, status: ScanStatus) {
    val curLat = status.lat
    val curLon = status.lon
    if (!status.hasFix || curLat == null || curLon == null) {
        Text("No location context", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        return
    }
    val mPerDegLat = 111320.0
    val mPerDegLon = 111320.0 * cos(Math.toRadians(curLat))
    val eastM = (lon - curLon) * mPerDegLon
    val southM = (curLat - lat) * mPerDegLat
    val dist = hypot(eastM, southM)
    val bearing = (Math.toDegrees(atan2(eastM, -southM)) + 360.0) % 360.0

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(
            Modifier.size(100.dp)
                .background(MapGround, CardShape)
                .border(1.dp, Rule, CardShape)
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.minDimension / 2f - 10f
            drawCircle(Rule, radius = maxR, center = c, style = Stroke(1f))
            drawCircle(Rule, radius = maxR / 2f, center = c, style = Stroke(1f))
            val target = if (dist > 0.0) {
                val pxPerM = maxR / dist.toFloat()
                Offset(c.x + (eastM * pxPerM).toFloat(), c.y + (southM * pxPerM).toFloat())
            } else c
            drawLine(Muted, c, target, strokeWidth = 1f)
            drawCircle(Color.White, radius = 5f, center = c)
            drawCircle(Blue, radius = 3.5f, center = c)
            drawCircle(Critical, radius = 5f, center = target)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (dist < 1.0) "Here" else "${fmtMetres(dist).removePrefix("~")} away",
                color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace
            )
            if (dist >= 1.0) Text("${compass(bearing)} of your position", color = InkDim, fontSize = 11.sp)
            Text("Centre is where you are now", color = Muted, fontSize = 10.sp)
            Text("Outer ring = ${fmtMetres(dist).removePrefix("~")}", color = Muted, fontSize = 10.sp)
        }
    }
}

// ── Cell / IMSI screen ───────────────────────────────────────────────────

@Composable
private fun CellScreen(onShowExplainer: ((CatcherFinding) -> Unit)? = null) {
    val cell by Registry.cell.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "§header") {
            Column(Modifier.padding(top = 20.dp)) {
                Text("CELLULAR", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Baseline-based IMSI catcher detection · no account required", color = Muted, fontSize = 12.sp)
            }
        }

        if (!cell.available) {
            item(key = "§unavailable") {
                Box(
                    Modifier.fillMaxWidth().background(Panel, CardShape).padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(cell.reason ?: "Cell data unavailable", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            item(key = "§risk") { CatcherRiskBadge(cell) }

            val c = cell.cell
            if (c != null) {
                item(key = "§serving") {
                    PanelBox {
                        SectionLabel("SERVING CELL")
                        CellInfoRow("Technology", c.rat.label)
                        CellInfoRow("Cell ID", c.cellId)
                        CellInfoRow("MCC / MNC", "${c.mcc ?: "?"} / ${c.mnc ?: "?"}")
                        CellInfoRow("Tracking Area", c.tac ?: "Unknown")
                        CellInfoRow("Signal", c.signalDbm?.let { fmtDbm(it) } ?: "?? dBm")
                        CellInfoRow("Neighbours", "${c.neighbors ?: "??"}")
                        CellInfoRow("Observed", fmtTime(c.ts))
                    }
                }
            }

            item(key = "§baseline") {
                Column(Modifier.fillMaxWidth().background(Panel, CardShape).padding(12.dp)) {
                    Text("Baseline maturity", color = InkDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { cell.maturity },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (cell.mature) Clear else Caution,
                        trackColor = Rule
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (cell.mature) "${plural(cell.knownCells, "cell")} · ${plural(cell.visits, "visit")} · Mature"
                        else "${cell.visits}/10 visits — keep scanning in your usual locations; a visit is a " +
                            "return to an area after time spent somewhere else",
                        color = Muted, fontSize = 12.sp
                    )
                }
            }

            if (cell.findings.isNotEmpty()) {
                item(key = "§indicators") {
                    Text(
                        "ACTIVE INDICATORS (${cell.findings.size})", color = Critical, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(cell.findings, key = { it.id }) { f ->
                    FindingRow(
                        f = f,
                        onShowExplainer = if (f.severity == Severity.CRITICAL || f.severity == Severity.HIGH)
                            { { onShowExplainer?.invoke(f) } } else null
                    )
                }
            } else {
                item(key = "§no-indicators") {
                    Text("No active indicators against the current baseline.", color = Clear, fontSize = 12.sp)
                }
            }

            if (cell.score > 0) {
                item(key = "§meaning") { WhatThisMeans(cell.score) }
            }
        }

        item(key = "§footer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CatcherRiskBadge(cell: CellStatus) {
    val col = threatColor(cell.level)
    val (title, desc) = when (cell.level) {
        Threat.CRITICAL -> "RISK: CRITICAL" to "Multiple anomaly indicators — strong evidence of an IMSI catcher"
        Threat.HIGH -> "RISK: HIGH" to "Significant anomalies detected against your baseline"
        Threat.MEDIUM -> "RISK: MEDIUM" to "Some indicators present"
        Threat.LOW -> "RISK: LOW" to "Minor indicators"
        Threat.NONE -> "RISK: NONE" to "No anomalies against baseline"
    }
    Row(
        Modifier.fillMaxWidth()
            .background(col.copy(alpha = 0.12f), CardShape)
            .border(1.dp, col.copy(alpha = 0.5f), CardShape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            title, color = Ground, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
            modifier = Modifier.background(col, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(desc, color = col, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Score ${cell.score}/100", color = col.copy(alpha = 0.8f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun WhatThisMeans(score: Int) {
    val text = when {
        score < 30 -> "Low baseline anomaly. May be normal network fluctuation."
        score < 50 -> "Moderate anomaly. Elevated risk of cell tower spoofing. Consider moving."
        score < 80 -> "High anomaly. Possible IMSI catcher nearby. Avoid making sensitive calls."
        else -> "CRITICAL. Strong evidence of active IMSI catcher. Turn off mobile data. Use WiFi with VPN."
    }
    val col = meterColor(score / 100f)
    Column(
        Modifier.fillMaxWidth()
            .background(Panel, CardShape)
            .border(1.dp, col.copy(alpha = 0.5f), CardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SectionLabel("WHAT THIS MEANS", col)
        Text(text, color = Ink, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun CellInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = InkDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun FindingRow(f: CatcherFinding, onShowExplainer: (() -> Unit)? = null) {
    val col = severityColor(f.severity)
    val clickMod = if (onShowExplainer != null) Modifier.clickable { onShowExplainer() } else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Panel)
            .then(clickMod)
            .height(IntrinsicSize.Min)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(col))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(f.title, color = col, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                ThreatBadge(f.severity.name, col)
                if (onShowExplainer != null) {
                    Text("?", color = col.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 2.dp))
                }
            }
            Text(f.detail, color = InkDim, fontSize = 12.sp, lineHeight = 17.sp)
            if (onShowExplainer != null) {
                Text("Tap for explanation and response guidance", color = col.copy(alpha = 0.6f),
                    fontSize = 10.sp, letterSpacing = 0.5.sp)
            }
        }
    }
}

// ── NFC screen ─────────────────────────────────────────────────────────

/**
 * DESFire and MIFARE Plus are ISO 14443-4 and so technically emulable, but they
 * authenticate with a challenge the phone cannot answer; only the static exchanges
 * a reader happens not to challenge can be replayed. Plain ISO 14443-4 is the case
 * HCE actually covers.
 */
private val CardProfile.hceLimited: Boolean
    get() = this == CardProfile.MIFARE_DESFIRE || this == CardProfile.MIFARE_PLUS

private const val HCE_LIMITED_CAVEAT = "Static replay only — readers that challenge the card will reject it"

@Composable
private fun NfcScreen(
    onAddToVault: (NfcTag, String, Boolean) -> Unit,
    onRemoveVault: (String) -> Unit,
    onEmulate: (VaultCard?) -> Unit,
    onUnlockVault: () -> Unit,
    onLockVault: () -> Unit,
    onEraseVault: () -> Unit
) {
    val tags       by Registry.nfc.collectAsStateWithLifecycle()
    val vaultState by Registry.vault.collectAsStateWithLifecycle()
    val armed      by Registry.emulating.collectAsStateWithLifecycle()
    val replace    by Registry.replacePrompt.collectAsStateWithLifecycle()
    val emulId = armed?.id
    // Cards exist here only while the vault is unlocked.
    val vault = (vaultState as? VaultState.Unlocked)?.cards.orEmpty()
    val vaultOpen = vaultState is VaultState.Unlocked
    var confirmErase by remember { mutableStateOf(false) }

    // The vault refused to overwrite a stored card with this UID; ask.
    val pendingReplace = replace
    if (pendingReplace != null) {
        AlertDialog(
            onDismissRequest = { Registry.setReplacePrompt(null) },
            containerColor = Panel,
            title = { Text("Replace existing card?", color = Ink, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "\"${pendingReplace.existing.label}\" is already in the vault with UID " +
                        "${pendingReplace.existing.uid}. Replace it with this scan, saved as " +
                        "\"${pendingReplace.label}\"? The stored copy is overwritten.",
                    color = InkDim, fontSize = 13.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { onAddToVault(pendingReplace.tag, pendingReplace.label, true) }) {
                    Text("REPLACE", color = Caution, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { Registry.setReplacePrompt(null) }) {
                    Text("KEEP EXISTING", color = Muted, letterSpacing = 1.sp)
                }
            }
        )
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            containerColor = Panel,
            title = { Text("Erase the vault?", color = Ink, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "The stored cards cannot be read, and erasing deletes them and the vault key " +
                        "permanently. Cards you still have physically can be scanned and saved again.",
                    color = InkDim, fontSize = 13.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmErase = false; onEraseVault() }) {
                    Text("ERASE", color = Critical, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmErase = false }) {
                    Text("CANCEL", color = Muted, letterSpacing = 1.sp)
                }
            }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header
        item(key = "hdr") {
            Spacer(Modifier.height(20.dp))
            Text("NFC & CARD VAULT", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Identify cards · store credentials securely · emulate access cards",
                color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
        }

        // Active emulation banner
        val armedCard = armed
        if (armedCard != null) {
            item(key = "emul_banner") {
                Row(
                    Modifier.fillMaxWidth().clip(CardShape).background(Blue.copy(alpha = 0.15f))
                        .border(1.dp, Blue.copy(alpha = 0.4f), CardShape).padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("EMULATING", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(armedCard.label, color = Ink, fontSize = 14.sp)
                        Text(
                            "NFC tag reading is paused while emulating. Unlock the phone and hold " +
                                "its back against the reader — Aegis can stay open or be in the background.",
                            color = Muted, fontSize = 12.sp
                        )
                    }
                    TextButton(onClick = { onEmulate(null) }) {
                        Text("STOP", color = Critical, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // Vault section
        item(key = "vault_hdr") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("VAULT", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                if (vaultState is VaultState.Unlocked) {
                    TextButton(
                        onClick = onLockVault,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text("LOCK", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp) }
                } else {
                    Text("AES-256-GCM · Android Keystore (hardware TEE)", color = Muted.copy(alpha = 0.6f), fontSize = 10.sp)
                }
            }
        }
        when (val state = vaultState) {
            VaultState.Locked, VaultState.Unlocking -> item(key = "vault_locked") {
                PanelBox {
                    Text("Vault locked", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Stored cards are encrypted with a hardware key that only works after you " +
                            "confirm it's you with your fingerprint, face, or screen-lock PIN.",
                        color = Muted, fontSize = 12.sp, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = onUnlockVault,
                        enabled = state == VaultState.Locked,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF12161D)),
                        shape = CardShape, modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state == VaultState.Unlocking) "WAITING FOR AUTHENTICATION…" else "UNLOCK",
                            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            is VaultState.Failed -> item(key = "vault_failed") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NoticeBanner(Critical, "VAULT", state.message)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.retryable) {
                            TextButton(onClick = onUnlockVault,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                Text("TRY AGAIN", color = Accent, fontSize = 11.sp, letterSpacing = 1.sp)
                            }
                        }
                        if (state.erasable) {
                            TextButton(onClick = { confirmErase = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                Text("ERASE VAULT", color = Critical, fontSize = 11.sp, letterSpacing = 1.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            is VaultState.Unlocked -> if (state.cards.isEmpty()) {
                item(key = "vault_empty") {
                    Text("No cards stored. Scan a card below and tap + VAULT to save it.",
                        color = InkDim, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                }
            } else {
                items(state.cards, key = { "v_${it.id}" }) { card ->
                    VaultCardRow(card = card, emulId = emulId, onEmulate = onEmulate, onRemove = { onRemoveVault(card.id) })
                }
            }
        }

        // Divider
        item(key = "divider") {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("SCANNED TAGS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                if (tags.isNotEmpty()) {
                    TextButton(
                        onClick = { Registry.clearNfc() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text("CLEAR", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp) }
                }
            }
        }

        if (tags.isEmpty()) {
            item(key = "scan_empty") {
                Text("No tags scanned yet.", color = InkDim, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text("Hold phone's NFC area to surfaces: desk undersides, bag linings, door frames, car seats.",
                    color = Muted, fontSize = 12.sp)
            }
        } else {
            val suspicious = tags.count { it.suspicious }
            item(key = "scan_count") {
                Text(
                    "${plural(tags.size, "tag")} · $suspicious suspicious",
                    color = if (suspicious > 0) Critical else Muted,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
            items(tags, key = { "t_${it.uid}" }) { tag ->
                NfcTagRow(
                    t = tag,
                    inVault = vault.any { it.uid == tag.uid },
                    vaultOpen = vaultOpen,
                    onAddToVault = { label -> onAddToVault(tag, label, false) },
                    onUnlockVault = onUnlockVault
                )
            }
        }

        item(key = "footer") { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun VaultCardRow(
    card: VaultCard,
    emulId: String?,
    onEmulate: (VaultCard?) -> Unit,
    onRemove: () -> Unit
) {
    val isEmulating = card.id == emulId
    val stripe = if (isEmulating) Blue else Clear
    Row(Modifier.fillMaxWidth().clip(CardShape).background(Panel).height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(stripe))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(card.label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    isEmulating -> ThreatBadge("ACTIVE", Blue)
                    card.profile.hceLimited -> ThreatBadge("HCE LIMITED", Caution)
                    card.profile.hceCapable -> ThreatBadge("HCE OK", Clear)
                    else -> ThreatBadge("STORED", Muted)
                }
            }
            Text("${card.profile.label} · UID ${card.uid}", color = Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            if (!card.profile.hceCapable) {
                Text("This card type uses a proprietary RF protocol — HCE emulation is not possible. UID and type are stored for identification.",
                    color = Muted, fontSize = 11.sp)
            } else if (card.profile.hceLimited) {
                Text(HCE_LIMITED_CAVEAT, color = Caution.copy(alpha = 0.9f), fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(card.addedTs), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (card.profile.hceCapable) {
                        TextButton(
                            onClick = { onEmulate(if (isEmulating) null else card) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                if (isEmulating) "STOP" else "EMULATE",
                                color = if (isEmulating) Critical else Blue,
                                fontSize = 11.sp, letterSpacing = 1.sp
                            )
                        }
                    }
                    TextButton(
                        onClick = onRemove,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text("REMOVE", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp) }
                }
            }
        }
    }
}

@Composable
private fun NfcTagRow(
    t: NfcTag,
    inVault: Boolean,
    vaultOpen: Boolean,
    onAddToVault: (String) -> Unit,
    onUnlockVault: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(t.uid) { mutableStateOf(false) }
    var addingLabel by remember(t.uid) { mutableStateOf(false) }
    var labelText by remember(t.uid) { mutableStateOf("") }
    LaunchedEffect(copied) { if (copied) { delay(1500L); copied = false } }

    val isPayment = t.profile in setOf(CardProfile.EMV_VISA, CardProfile.EMV_MASTERCARD,
        CardProfile.EMV_AMEX, CardProfile.EMV_OTHER)
    val stripe = when {
        t.suspicious -> Critical
        isPayment    -> Caution
        else         -> Clear
    }

    Row(Modifier.fillMaxWidth().clip(CardShape).background(Panel).height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(stripe))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.uid, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                ThreatBadge(when {
                    t.suspicious -> "SUSPECT"
                    isPayment    -> "PAYMENT"
                    else         -> "CLEAN"
                }, stripe)
            }

            // Profile + ATQA/SAK
            val techLine = buildString {
                append(t.type)
                if (t.atqa != null && t.sak != null) append(" · ATQA ${t.atqa} SAK ${t.sak}")
                if (t.paymentNetwork != null) append(" · ${t.paymentNetwork}")
            }
            Text(techLine, color = Accent, fontSize = 12.sp)
            Text(t.note, color = if (t.suspicious) Critical.copy(alpha = 0.9f) else Muted, fontSize = 12.sp)

            // Skimmer flags
            t.skimmerFlags.forEach { flag ->
                NoticeBanner(Critical, "ANOMALY", flag)
            }
            if (isPayment) {
                NoticeBanner(Caution, "PAYMENT", "Public card metadata only — no secret data readable. Not a tracker.")
            }
            if (t.payload != null) {
                Text("Data: ${t.payload.take(80)}", color = InkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }

            // HCE capability hint
            if (t.profile.hceLimited && !isPayment) {
                Text("HCE limited — $HCE_LIMITED_CAVEAT. Add to vault to try it.",
                    color = Caution.copy(alpha = 0.9f), fontSize = 11.sp)
            } else if (t.profile.hceCapable && !isPayment) {
                Text("This card type can be emulated via HCE — add to vault to use phone as card.",
                    color = Clear.copy(alpha = 0.8f), fontSize = 11.sp)
            }

            // Add-to-vault inline input
            if (addingLabel) {
                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it },
                    label = { Text("Card label", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Rule,
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { addingLabel = false; labelText = "" },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("CANCEL", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                    TextButton(
                        onClick = {
                            if (labelText.isNotBlank()) {
                                onAddToVault(labelText.trim())
                                addingLabel = false; labelText = ""
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        enabled = labelText.isNotBlank()
                    ) { Text("SAVE", color = Clear, fontSize = 11.sp, letterSpacing = 1.sp) }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(t.ts), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Whether the tag is already stored is only known while the vault
                    // is open, so a locked vault offers the unlock, not the save: with
                    // the key's auth window still warm, a save from here used to go
                    // through without a prompt and replace the stored card unseen.
                    if (!vaultOpen && !isPayment) {
                        TextButton(
                            onClick = onUnlockVault,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("UNLOCK TO SAVE", color = Accent, fontSize = 11.sp, letterSpacing = 1.sp) }
                    } else if (!inVault && !isPayment && !addingLabel) {
                        TextButton(
                            onClick = { addingLabel = true; labelText = t.profile.label },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("+ VAULT", color = Clear, fontSize = 11.sp, letterSpacing = 1.sp) }
                    } else if (inVault) {
                        Text("IN VAULT", color = Clear.copy(alpha = 0.6f), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(t.uid)); copied = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(if (copied) "COPIED ✓" else "COPY UID",
                            color = if (copied) Clear else InkDim, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }
    }
}

// ── Device health screen ────────────────────────────────────────────────

@Composable
private fun DeviceScreen(onShown: () -> Unit) {
    val health by Registry.phoneHealth.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    // Live microphone and camera monitoring only runs inside the scanner service;
    // the static findings are re-checked by the Activity whenever this tab appears.
    val liveMonitoring = status.scanning
    LaunchedEffect(Unit) { onShown() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("DEVICE HEALTH", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Mic, camera, spyware access — what can see and hear you right now",
                    color = Muted, fontSize = 12.sp)
            }
            val levelColor = when (health.level) {
                Threat.CRITICAL -> Critical
                Threat.HIGH -> Accent
                Threat.MEDIUM -> Caution
                Threat.LOW -> Caution
                Threat.NONE -> Clear
            }
            Text(
                health.level.name,
                color = levelColor, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.background(levelColor.copy(alpha = 0.12f), CardShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // ── Active mic / camera — most urgent, always at top ──────────────
            // Android tells a third-party app that a recording or a camera is in use,
            // but not which app — so nothing here names one. The status-bar privacy
            // indicator and Settings → Privacy dashboard show the app itself.
            // A live sensor is HIGH, not CRITICAL: it is also what every video call
            // looks like. The banner says what is known and where to find the rest.
            if (health.activeRecordings > 0) {
                item(key = "mic_banner") {
                    ActiveSensorBanner(
                        label = "MICROPHONE ACTIVE",
                        color = Accent,
                        detail = if (health.activeRecordings == 1) "An app is recording audio."
                            else "Apps are recording audio (${health.activeRecordings} active recordings).",
                        lines = listOf(
                            "Android does not tell Aegis which app. Tap the green privacy dot in the " +
                                "status bar, or open Settings → Security & privacy → Privacy dashboard."
                        )
                    )
                }
            }
            if (health.cameraInUse) {
                item(key = "cam_banner") {
                    ActiveSensorBanner(
                        label = "CAMERA ACTIVE",
                        color = Accent,
                        detail = "Camera in use by another app",
                        lines = listOf(
                            "Android does not tell Aegis which app. Tap the green privacy dot in the " +
                                "status bar, or open Settings → Security & privacy → Privacy dashboard."
                        )
                    )
                }
            }

            if (!health.sensorActive) {
                item(key = "sensors_ok") {
                    Row(
                        Modifier.fillMaxWidth().clip(CardShape).background(Panel)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // With the scanner off nobody is watching the sensors, so
                        // nothing can be asserted about them.
                        Canvas(Modifier.size(8.dp)) { drawCircle(if (liveMonitoring) Clear else Muted) }
                        Text(
                            if (liveMonitoring) "No app is currently using the microphone or camera"
                            else "Live mic/camera monitoring runs while scanning is on",
                            color = if (liveMonitoring) Clear else Muted, fontSize = 13.sp
                        )
                    }
                }
            }

            // ── Divider ────────────────────────────────────────────────────────
            item(key = "div") { Spacer(Modifier.height(4.dp)) }

            // ── Static findings ────────────────────────────────────────────────
            if (health.findingsScannedTs == 0L) {
                item(key = "checking") {
                    Column(
                        Modifier.fillMaxWidth().clip(CardShape).background(Panel).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Checking…", color = InkDim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Looking for accessibility services, device admin apps and debug interfaces.",
                            color = Muted, fontSize = 12.sp
                        )
                    }
                }
            } else if (health.findings.isEmpty()) {
                item(key = "all_clear") {
                    Column(
                        Modifier.fillMaxWidth().clip(CardShape).background(Panel).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("No issues found", color = Clear, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "No accessibility services, device admin apps, or debug interfaces that could be used " +
                                "for surveillance. Checked ${fmtClock(health.findingsScannedTs)}.",
                            color = Muted, fontSize = 12.sp
                        )
                    }
                }
            } else {
                items(health.findings, key = { it.id }) { f ->
                    HealthFindingRow(f)
                }
            }

            item(key = "§footer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ActiveSensorBanner(label: String, color: Color, detail: String, lines: List<String>) {
    Row(
        Modifier.fillMaxWidth().clip(CardShape).background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.6f), CardShape).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Canvas(Modifier.size(8.dp).padding(top = 3.dp)) { drawCircle(color) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(detail, color = Ink, fontSize = 13.sp)
            lines.forEach { line ->
                Text("  • $line", color = InkDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun HealthFindingRow(f: PhoneHealthFinding) {
    val stripe = when (f.severity) {
        Severity.CRITICAL -> Critical
        Severity.HIGH -> Critical
        Severity.MEDIUM -> Caution
        Severity.LOW -> Muted
    }
    Row(Modifier.fillMaxWidth().clip(CardShape).background(Panel).height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(stripe))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    f.title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                ThreatBadge(f.severity.name, stripe)
            }
            Text(f.category, color = Accent, fontSize = 11.sp, letterSpacing = 0.5.sp)
            Text(f.detail, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

// ── WiFi screen ────────────────────────────────────────────────────────

private fun wifiReason(reason: String): String = when (reason) {
    "known_catcher_ssid" -> "Default SSID of known interception equipment"
    "carrier_open_network" -> "Carrier name on an unencrypted network — likely bait"
    "open_twin_of_secured" -> "Open copy of a network that is encrypted nearby — evil twin"
    "open_unsecured" -> "Open, unsecured network — traffic can be intercepted"
    else -> reason.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
}

@Composable
private fun WifiScreen() {
    val anomalies by Registry.wifi.collectAsStateWithLifecycle()
    val sorted = remember(anomalies) {
        anomalies.sortedWith(compareByDescending<WifiAnomaly> { it.threat.ordinal }.thenByDescending { it.ts })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("WiFi Anomaly Scanner", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text("Detects IMSI catcher bait networks and suspicious access points", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        if (sorted.isEmpty()) {
            Box(Modifier.fillMaxWidth().background(Panel, CardShape).padding(16.dp)) {
                Text(
                    "No WiFi anomalies detected. Scan is passive — results update automatically.",
                    color = InkDim, fontSize = 13.sp, lineHeight = 18.sp
                )
            }
        } else {
            val critical = sorted.count { it.threat == Threat.CRITICAL }
            val high = sorted.count { it.threat == Threat.HIGH }
            Text(
                "${plural(sorted.size, "anomaly").replace("anomalys", "anomalies")} · $critical critical · $high high",
                color = if (critical > 0) Critical else if (high > 0) Accent else Muted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(sorted, key = { i, a -> "${a.bssid}|${a.reason}|${a.ts}|$i" }) { _, a -> WifiRow(a) }
                item(key = "§footer") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun WifiRow(a: WifiAnomaly) {
    val col = threatColor(a.threat)
    Row(Modifier.fillMaxWidth().clip(CardShape).background(Panel).height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(col))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    a.ssid.ifBlank { "<hidden SSID>" }, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                ThreatBadge(a.threat.name, col)
            }
            Text(a.bssid, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(wifiReason(a.reason), color = col, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(fmtDbm(a.rssi), color = InkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(fmtTime(a.ts), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── Threat explainer bottom sheet ───────────────────────────────────────────

private data class ExplainerContent(
    val headline: String,
    val whatIsThis: String,
    val whatToDo: String,
    val howCertain: String,
    val severity: Color
)

private fun bleExplainerContent(d: Detection): ExplainerContent {
    val col = when (d.threat) {
        Threat.CRITICAL -> Color(0xFFF2545B)
        Threat.HIGH -> Color(0xFFFF7A3D)
        else -> Color(0xFFE8B33D)
    }
    return when {
        d.following -> ExplainerContent(
            headline = "Confirmed Following — ${d.name}",
            whatIsThis = "A Bluetooth tracker has been verified as following your movements. " +
                "It appeared at multiple locations ${d.displacementM.roundToInt()} metres apart while you were " +
                "moving, which cannot be explained by proximity alone. ${d.tracker?.let { "This matches a known ${it.brand} ${it.label}." } ?: "No commercial tracker signature matched, but the movement pattern is conclusive."}",
            whatToDo = "Stop and search the vehicle or your belongings now. Check: wheel wells, bumper covers and cavities, " +
                "OBD-II diagnostic port (under dashboard), under seats and floor mats, inside bag linings and seams. " +
                "If found, do not discard it on the roadside — it is evidence. Photograph it in place before removing it. " +
                "Consider filing a police report and contacting legal counsel.",
            howCertain = "High confidence. Confirmation requires the device to appear at locations " +
                "${d.displacementM.roundToInt()} m apart during your movement. " +
                "Threat score: ${d.score}/100 across ${d.sightings} sightings.",
            severity = col
        )
        d.persistent && d.identified -> ExplainerContent(
            headline = "Known Tracker — ${d.name}",
            whatIsThis = "A ${d.tracker?.brand ?: "commercial"} ${d.tracker?.label ?: "tracker"} has been " +
                "present for an extended period. Known trackers are purpose-built surveillance devices. " +
                "Extended presence without confirmed movement suggests it may be attached to your vehicle or belongings.",
            whatToDo = "Monitor this device. If it remains present as you travel, it will be promoted to " +
                "FOLLOWING status automatically. If you believe it does not belong to you, physically search " +
                "your vehicle and belongings. Pay attention to whether the signal strength (RSSI) stays consistent, " +
                "which would indicate it is on or very near your person.",
            howCertain = "Medium-to-high confidence. Device is identified as a commercial tracker with " +
                "${d.sightings} sightings over an extended period. Following confirmation requires GPS movement. " +
                "Threat score: ${d.score}/100.",
            severity = col
        )
        d.persistent -> ExplainerContent(
            headline = "Persistent Unknown Device — ${d.name}",
            whatIsThis = "An unidentified Bluetooth device has been present continuously for an extended period. " +
                "This could be a benign device (a neighbour's router, a fixed sensor) or a tracker that does not " +
                "match the known signature database. Its persistence pattern is unusual for a passing device.",
            whatToDo = "Watch for this device across different locations. If it follows you as you travel, " +
                "it will be flagged as FOLLOWING. If it is stationary and you remain in one place, it is " +
                "likely environmental. Mark it as TRUSTED below if you know what it is.",
            howCertain = "Low-to-medium confidence. No known tracker signature matched — flagged on " +
                "behaviour alone. Threat score: ${d.score}/100 over ${d.sightings} sightings.",
            severity = col
        )
        else -> ExplainerContent(
            headline = "${d.threat.name} Threat — ${d.name}",
            whatIsThis = "This device has characteristics associated with surveillance trackers. " +
                "${d.tracker?.let { "It matches the signature of a ${it.brand} ${it.label}." } ?: "No exact tracker signature matched, but its signal pattern warrants monitoring."}",
            whatToDo = "Continue monitoring. The scan needs more time and movement to determine whether " +
                "this device is following you. Keep the scan running as you travel.",
            howCertain = "Building confidence. Score ${d.score}/100 · ${d.sightings} sightings. " +
                "GPS confirmation of following requires approximately 300 m of shared movement.",
            severity = col
        )
    }
}

private fun cellExplainerContent(f: CatcherFinding): ExplainerContent {
    val col = when (f.severity) {
        Severity.CRITICAL -> Color(0xFFF2545B)
        Severity.HIGH -> Color(0xFFFF7A3D)
        Severity.MEDIUM -> Color(0xFFE8B33D)
        Severity.LOW -> Color(0xFF6F7A8B)
    }
    // Findings that can fire for more than one cell carry it after a colon, so the
    // explainer keys off the heuristic name in front of it.
    val (what, todo, certain) = when (f.id.substringBefore(':')) {
        "rat_downgrade" -> Triple(
            "Your device has been forced from a newer radio technology (4G/5G) down to an older one (2G/3G). " +
            "IMSI catchers do this deliberately because older protocols have weaker encryption and are easier " +
            "to intercept. Legitimate networks only downgrade in areas with no newer coverage.",
            "Avoid making voice calls or sending SMS until you leave the area. Use end-to-end encrypted " +
            "messaging apps over WiFi or data. Do not transmit sensitive information. Move away from the " +
            "area and observe whether the technology level returns to normal.",
            "High confidence indicator. A drop to 2G/3G within half an hour of 4G/5G serving you at the " +
            "same spot, without you having moved, is a primary IMSI catcher signature. Moving resets " +
            "the comparison, so a drive into a basement car park does not count."
        )
        "cellid_tac_mismatch" -> Triple(
            "A cell tower ID that you have seen before is now claiming to be in a different area (tracking " +
            "area code). Real base stations have fixed, permanent area assignments. A portable IMSI catcher " +
            "moving through the area will produce inconsistent area codes.",
            "Monitor for additional indicators. A single mismatch could be a network reconfiguration, but " +
            "combined with other findings it is significant. Avoid sensitive calls in this area.",
            "High confidence indicator when combined with other findings. Alone it may reflect legitimate " +
            "network changes, but it warrants heightened caution."
        )
        "tac_change_stationary" -> Triple(
            "Your device registered a different tracking area while you were not moving. " +
            "Tracking area changes normally happen when you travel across cell boundaries. When you are " +
            "stationary, a change indicates a new transmitter has appeared near you and forced re-registration.",
            "Note your exact location and time. A stationary area change combined with other indicators " +
            "is a strong surveillance signal. Consider moving away from the area.",
            "Medium confidence. Stationary area changes can occur due to legitimate network maintenance, " +
            "but are uncommon and warrant attention alongside other findings."
        )
        "signal_outlier" -> Triple(
            "The cell signal is significantly stronger than the historical maximum for this area. " +
            "A portable transmitter placed near you — in a vehicle, building, or backpack — produces " +
            "a much stronger signal than a distant tower.",
            "Check your surroundings for parked vehicles with unusual equipment, people lingering nearby, " +
            "or recently placed objects. A strong signal alone is insufficient for action, but note the " +
            "location and observe whether it moves with you.",
            "Medium confidence. Signal strength varies for many reasons. This indicator is most meaningful " +
            "when combined with technology downgrade or unknown cell findings."
        )
        "unknown_cell" -> Triple(
            "A cell tower that has never appeared in this area before has become your serving cell. " +
            "You have visited this area at least 20 separate times, and the tower infrastructure here is well-established. " +
            "Portable IMSI catchers appear as unknown cells in familiar areas.",
            "A new cell at a familiar location deserves caution. If you also see technology downgrade or " +
            "signal strength anomalies, treat this as a serious surveillance indicator. Do not make sensitive " +
            "calls. Move away and observe whether the new cell disappears.",
            "Medium confidence. New towers do get installed occasionally, but this detection requires " +
            "at least 20 separate visits to the area (returns more than 30 minutes apart) before triggering."
        )
        "ephemeral_cell", "ephemeral_cell_strict" -> Triple(
            "A cell tower appeared briefly as your serving cell, then vanished. Real base stations " +
            "broadcast continuously — they do not appear for a few minutes and disappear. " +
            "A portable surveillance device driven into and away from an area looks exactly like this.",
            "Note the time and location. An ephemeral cell that appeared and disappeared suggests a " +
            "mobile surveillance asset that has moved on. Review your surroundings at the time of detection.",
            "Medium-to-high confidence. Ephemeral cells have few legitimate explanations. " +
            "A duration under 90 seconds is the strictest threshold."
        )
        "cell_flapping" -> Triple(
            "Your device has switched between multiple different cell towers rapidly while you were " +
            "stationary. IMSI catchers force repeated re-registrations to capture authentication events. " +
            "Normal network handover does not produce this pattern at a single location.",
            "Avoid making calls or sending messages while this is occurring. If it persists, " +
            "move away from the area. The flapping pattern suggests active interference with your " +
            "device's network registration.",
            "Medium confidence. Rapid cell switching can also result from poor coverage areas, " +
            "but is unusual when stationary in a normally covered location."
        )
        "no_neighbors" -> Triple(
            "Your serving cell is reporting zero neighbouring cells. Real base stations always have " +
            "overlapping coverage with adjacent towers — this is fundamental to how cellular networks " +
            "are designed. A portable IMSI catcher operating alone has no neighbours to report.",
            "This is a supporting indicator rather than an action trigger alone. Watch for it " +
            "alongside technology downgrade or unknown cell findings, at which point you should " +
            "follow the high-confidence response guidance.",
            "Low confidence as a standalone indicator. Meaningful when combined with other findings."
        )
        "timing_advance_zero" -> Triple(
            "LTE timing advance of zero means the transmitter is within approximately 78 metres of " +
            "your device. Macro cell towers are never this close to you. A portable IMSI catcher in " +
            "a parked vehicle or nearby building would produce exactly this reading.",
            "A transmitter within 78 metres is immediately actionable. Scan your visual surroundings " +
            "for parked vehicles with rooftop antennas or unusual equipment. Do not make sensitive " +
            "calls. Move away and observe whether the indicator follows you.",
            "High confidence. LTE timing advance is a precise physical measurement — zero means very " +
            "close proximity to the transmitter."
        )
        "signal_spike" -> Triple(
            "The signal strength on your current cell tower jumped suddenly between two consecutive " +
            "readings. A macro cell tower at a fixed location has a stable signal. A mobile transmitter " +
            "moving towards you — in a vehicle, for example — produces exactly this kind of spike.",
            "A sudden signal spike suggests a mobile surveillance asset moving closer to your position. " +
            "Be aware of your surroundings. If the spike is combined with other indicators, follow " +
            "the high-confidence response protocol immediately.",
            "Medium confidence. Signal spikes can result from device movements, building reflections, " +
            "or other environmental factors, but are notable when combined with other findings."
        )
        "rat_oscillation" -> Triple(
            "Your device has switched between multiple radio technologies (2G, 3G, 4G) rapidly in " +
            "a short window. IMSI catchers force devices through technology cycles to capture separate " +
            "authentication events on each technology, revealing the device's IMSI.",
            "Multiple technology switches in a short window strongly indicate active interference. " +
            "Avoid all calls and data use. Use WiFi with a VPN for any communications. " +
            "Move out of the area and observe whether the switching stops.",
            "High confidence. Rapid multi-technology oscillation is a strong signature of active " +
            "IMSI capture operations."
        )
        else -> Triple(
            "An anomaly was detected in your cellular environment that deviates from your established " +
            "baseline. The specific indicator ID is: ${f.id}. See the full detail for more context.",
            "Monitor for additional indicators. If multiple anomalies appear simultaneously, " +
            "treat the situation as a potential IMSI catcher and avoid sensitive communications.",
            "Confidence level: ${f.severity.name.lowercase()}."
        )
    }
    return ExplainerContent(
        headline = f.title,
        whatIsThis = what,
        whatToDo = todo,
        howCertain = certain,
        severity = col
    )
}

@Composable
private fun ThreatExplainerSheet(target: ExplainerTarget, onDismiss: () -> Unit) {
    val content = when (target) {
        is ExplainerTarget.BleDevice -> bleExplainerContent(target.detection)
        is ExplainerTarget.CellIndicator -> cellExplainerContent(target.finding)
    }

    Column(
        Modifier
            .fillMaxWidth()
            // These explainers run to several paragraphs. Without a scroll the tail of
            // the text and the CLOSE button fall off the bottom of the sheet on a
            // normal-height phone, leaving no way to read or dismiss them.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Drag handle (visual only)
        Box(
            Modifier
                .width(40.dp)
                .height(4.dp)
                .background(Muted.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                .align(Alignment.CenterHorizontally)
        )

        // Headline
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                content.headline,
                color = content.severity,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))

        // What is this?
        ExplainerSection(
            heading = "WHAT IS THIS?",
            body = content.whatIsThis,
            headingColor = Accent
        )

        Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))

        // What should I do?
        ExplainerSection(
            heading = "WHAT SHOULD I DO?",
            body = content.whatToDo,
            headingColor = Accent
        )

        Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))

        // How certain is this?
        ExplainerSection(
            heading = "HOW CERTAIN IS THIS?",
            body = content.howCertain,
            headingColor = Muted
        )

        // Dismiss button
        TextButton(
            onClick = onDismiss,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("CLOSE", color = Muted, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ExplainerSection(heading: String, body: String, headingColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            heading,
            color = headingColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
        Text(
            body,
            color = InkDim,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
    }
}
