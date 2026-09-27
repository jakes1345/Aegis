package com.xat.aegis

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.xat.aegis.comms.ActiveCall
import com.xat.aegis.comms.CallManager
import com.xat.aegis.comms.CallPhase
import com.xat.aegis.comms.COIN_SYMBOL
import com.xat.aegis.comms.ChatMessage
import com.xat.aegis.comms.ChatThread
import com.xat.aegis.comms.CommsMedia
import com.xat.aegis.comms.CommsWire
import com.xat.aegis.comms.MediaBody
import com.xat.aegis.comms.STATUS_CALL
import com.xat.aegis.comms.STATUS_PAYMENT
import com.xat.aegis.comms.STATUS_RECEIVING
import com.xat.aegis.comms.STATUS_VOICEMAIL
import com.xat.aegis.comms.VoicemailBody
import com.xat.aegis.comms.VoicemailPlayer
import com.xat.aegis.comms.durationLabel
import com.xat.aegis.comms.label
import com.xat.aegis.comms.preview
import com.xat.aegis.comms.CommsLog
import com.xat.aegis.comms.CommsNotifications
import com.xat.aegis.comms.CommsRepository
import com.xat.aegis.comms.Contact
import com.xat.aegis.comms.Direction
import com.xat.aegis.comms.PairingCode
import com.xat.aegis.comms.formatAegisNumber
import com.xat.aegis.comms.parseAegisNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Colour tokens local to this file ──────────────────────────────────────────

private val CGround   = Color(0xFF0E1116)
private val CPanel    = Color(0xFF161B23)
private val CPanelHi  = Color(0xFF1D2430)
private val CInk      = Color(0xFFE6EAF1)
private val CInkDim   = Color(0xFFA8B2C1)
private val CMuted    = Color(0xFF6F7A8B)
private val CRule     = Color(0xFF262E3A)
private val CAccent   = Color(0xFFFF7A3D)
private val CCritical = Color(0xFFF2545B)
private val CCaution  = Color(0xFFE8B33D)
private val CClear    = Color(0xFF3DB88A)
private val CBlue     = Color(0xFF4A8FD4)
private val CShape    = RoundedCornerShape(4.dp)

private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
private val dateTimeFmt = SimpleDateFormat("MMM d HH:mm", Locale.US)

private const val UI_PREFS = "comms_ui"
private const val KEY_MIC_ASKED = "microphone_asked"

private const val NOT_A_PHONE = "Aegis numbers are not phone numbers. They only reach other Aegis apps on the same relay; they cannot call or text a phone."

/** Where the COMMS tab is, apart from the conversation list. */
private sealed class CommsPage {
    object List : CommsPage()
    data class Thread(val peer: String) : CommsPage()
    data class Verify(val peer: String) : CommsPage()
    object MyCode : CommsPage()
    object Invite : CommsPage()
    object Settings : CommsPage()
}

/**
 * The COMMS tab: this device's Aegis number, its contacts and end-to-end
 * encrypted conversations, and the registration screen before all that.
 *
 * [openPeer] is a conversation to jump straight into (from a notification),
 * consumed once.
 */
@Composable
fun CommsScreen(
    openPeer: String?,
    onPeerConsumed: () -> Unit,
    openInvite: String? = null,
    onInviteConsumed: () -> Unit = {}
) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val activeCall by CallManager.call.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf<CommsPage>(CommsPage.List) }
    // An invite from a link: it fills in the setup screen, or, once this phone
    // has a number, offers to add the person who sent it.
    var invite by remember { mutableStateOf<PairingCode?>(null) }
    var inviterToAdd by remember { mutableStateOf<PairingCode?>(null) }
    var inviteError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openInvite) {
        if (openInvite != null) {
            val code = PairingCode.fromText(openInvite)
            when {
                code == null -> inviteError = "That invite link is incomplete; ask for it to be sent again."
                state.registered -> inviterToAdd = code
                else -> invite = code
            }
            onInviteConsumed()
        }
    }

    LaunchedEffect(openPeer) {
        if (openPeer != null) {
            page = CommsPage.Thread(openPeer)
            onPeerConsumed()
        }
    }

    // Calls need the microphone. Asked for once as soon as this phone has a
    // number, so the first incoming call is not also the first time the
    // question comes up, on a locked phone, with the caller waiting.
    val context = LocalContext.current
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(state.registered) {
        if (!state.registered || hasMicrophone(context)) return@LaunchedEffect
        val prefs = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIC_ASKED, false)) return@LaunchedEffect
        prefs.edit().putBoolean(KEY_MIC_ASKED, true).apply()
        askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }

    // A call, ringing or in progress, takes the whole tab; so does the offer to
    // leave a voicemail after a call this phone placed went unanswered.
    val call = activeCall
    val voicemail = rememberVoicemailOffer(call)
    if (voicemail != null && (call == null || call.phase == CallPhase.ENDED)) {
        VoicemailScreen(voicemail.contact, call, onDone = voicemail.dismiss)
        return
    }
    if (call != null) {
        InCallScreen(call)
        return
    }

    if (!state.registered) {
        SetupScreen(invite = invite, linkError = inviteError, onInvite = { invite = it; inviteError = null })
        return
    }
    inviterToAdd?.let { code ->
        AddInviterDialog(code, onDone = { inviterToAdd = null }, onAdded = { page = CommsPage.Thread(it) })
    }
    when (val p = page) {
        CommsPage.List -> ThreadListScreen(
            onOpen = { page = CommsPage.Thread(it) },
            onMyCode = { page = CommsPage.MyCode },
            onInvite = { page = CommsPage.Invite },
            onSettings = { page = CommsPage.Settings }
        )
        is CommsPage.Thread -> ThreadScreen(
            peer = p.peer,
            onBack = { page = CommsPage.List },
            onVerify = { page = CommsPage.Verify(p.peer) }
        )
        is CommsPage.Verify -> VerifyScreen(
            peer = p.peer,
            onBack = { page = CommsPage.Thread(p.peer) },
            onDeleted = { page = CommsPage.List }
        )
        CommsPage.MyCode -> MyCodeScreen(onBack = { page = CommsPage.List })
        CommsPage.Invite -> InviteScreen(onBack = { page = CommsPage.List })
        CommsPage.Settings -> CommsSettingsScreen(onBack = { page = CommsPage.List })
    }
}

// ── Shared pieces ────────────────────────────────────────────────────────────

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = CInk, unfocusedTextColor = CInk,
    focusedBorderColor = CAccent, unfocusedBorderColor = CRule,
    focusedLabelColor = CAccent, unfocusedLabelColor = CMuted,
    cursorColor = CAccent, focusedContainerColor = CPanel, unfocusedContainerColor = CPanel
)

@Composable
private fun Header(title: String, onBack: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("‹ BACK", color = CAccent, fontSize = 13.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.width(12.dp))
        }
        Text(title, color = CInk, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(CPanel, CShape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun SmallButton(label: String, colour: Color = CInkDim, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
        Text(label, color = if (enabled) colour else CMuted, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, colour: Color = CAccent, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = colour, contentColor = Color(0xFF12161D), disabledContainerColor = CPanelHi, disabledContentColor = CMuted),
        shape = CShape, modifier = Modifier.fillMaxWidth()
    ) { Text(label, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp) }
}

@Composable
private fun ToggleRow(label: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = CMuted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = CGround, checkedTrackColor = CAccent, uncheckedThumbColor = CInkDim, uncheckedTrackColor = CPanelHi, uncheckedBorderColor = CRule)
        )
    }
}

private fun hasMicrophone(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Opens Aegis's page in the system settings, where a blocked permission can be allowed. */
internal fun openAppDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Asks for the microphone if needed, then runs [onGranted]. A refusal used to
 * leave CALL and ACCEPT doing nothing at all; it is now said, and once Android
 * stops showing the dialog, the settings page where it can be allowed opens.
 * On a locked phone the lock screen is dismissed first, since the permission
 * dialog cannot show over it.
 */
@Composable
private fun rememberMicrophoneGate(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            onGranted()
        } else {
            val activity = context.findActivity()
            val blocked = activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            Toast.makeText(
                context,
                if (blocked) "The microphone is blocked for Aegis. Allow it under Permissions, then try again." else "Calls need the microphone.",
                Toast.LENGTH_LONG
            ).show()
            if (blocked) openAppDetails(context)
        }
    }
    return {
        if (hasMicrophone(context)) {
            onGranted()
        } else {
            val activity = context.findActivity()
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            if (activity != null && keyguard?.isKeyguardLocked == true) {
                keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() { launcher.launch(Manifest.permission.RECORD_AUDIO) }
                })
            } else {
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Launches the QR scanner and hands back the decoded pairing code. */
@Composable
private fun rememberCodeScanner(onCode: (PairingCode?) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text != null) onCode(PairingCode.decode(text))
    }
    return {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Point at the other person's Aegis code")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
        )
    }
}

private fun qrBitmap(text: String, sizePx: Int): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.CHARACTER_SET to "UTF-8")
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) for (x in 0 until sizePx) pixels[y * sizePx + x] = if (matrix[x, y]) 0xFF0E1116.toInt() else 0xFFE6EAF1.toInt()
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}

@Composable
private fun rememberTick(periodMs: Long = 30_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) { while (true) { delay(periodMs); now = System.currentTimeMillis() } }
    return now
}

private fun agoText(now: Long, ts: Long): String {
    val s = ((now - ts) / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> dateTimeFmt.format(Date(ts))
    }
}

private fun contactLabel(c: Contact) = c.name.ifBlank { formatAegisNumber(c.number) }

// ── Setup ────────────────────────────────────────────────────────────────────

@Composable
private fun SetupScreen(invite: PairingCode?, linkError: String?, onInvite: (PairingCode?) -> Unit) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var url by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var listed by rememberSaveable { mutableStateOf(true) }
    var inviteError by remember(linkError) { mutableStateOf(linkError) }
    val takeInvite: (PairingCode?) -> Unit = { code ->
        when {
            code == null -> inviteError = "That is not an Aegis invite."
            code.invite == null -> inviteError = "That is someone's contact code, not an invite. Ask them for an invite: COMMS → INVITE on their phone."
            else -> { inviteError = null; onInvite(code) }
        }
    }
    val scanInvite = rememberCodeScanner { takeInvite(it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("COMMS", color = CInk, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text("End-to-end encrypted messaging between Aegis apps, through a relay you run", color = CMuted, fontSize = 12.sp)

        if (invite != null) {
            Card {
                Text("Join with an invite", color = CInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Invited by", color = CMuted, fontSize = 11.sp)
                Text(invite.name.ifBlank { formatAegisNumber(invite.number) }, color = CAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${formatAegisNumber(invite.number)} · ${invite.relayUrl.removePrefix("https://")}", color = CInkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(
                    "This phone makes its own keys and gets its own Aegis number on their relay. " +
                        "${invite.name.ifBlank { "They" }} will be in your contacts, verified, with a first message telling them you joined.",
                    color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text("Your name shown to contacts (optional)", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors()
                )
                ToggleRow(
                    "Listed number",
                    if (listed) "Anyone with your number on this relay can add you." else "Unlisted: people can add you only by scanning your code. You can change this later.",
                    listed
                ) { listed = it }
                state.error?.let { Text(it, color = CCritical, fontSize = 12.sp) }
                PrimaryButton(if (state.busy) "REGISTERING…" else "REGISTER", enabled = !state.busy) {
                    scope.launch { CommsRepository.register(invite.relayUrl, "", name, listed, invite) }
                }
                SmallButton("USE A RELAY URL AND SECRET INSTEAD", CMuted) { onInvite(null) }
            }
            Card {
                Text("Not a phone number", color = CCaution, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(NOT_A_PHONE, color = CMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(16.dp))
            return@Column
        }

        Card {
            Text("Have an invite?", color = CInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Someone already on Aegis can invite you: COMMS → INVITE on their phone. Scan the QR code it shows, " +
                    "or copy the invite they sent you and paste it here. No relay URL or secret needed.",
                color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { inviteError = null; scanInvite() }, shape = CShape, border = BorderStroke(1.dp, CClear), modifier = Modifier.weight(1f)) {
                    Text("SCAN INVITE", color = CClear, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { takeInvite(clipboard.getText()?.text?.let { PairingCode.fromText(it) }) },
                    shape = CShape, border = BorderStroke(1.dp, CAccent), modifier = Modifier.weight(1f)
                ) {
                    Text("PASTE INVITE", color = CAccent, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                }
            }
            inviteError?.let { Text(it, color = CCritical, fontSize = 12.sp) }
        }

        Card {
            Text("Or run your own relay", color = CInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Deploy comms-worker from the Aegis repository to Cloudflare, then enter its URL and the enrollment " +
                    "secret you set. This phone generates its keys here, never shares the private half, and the relay " +
                    "hands it a random nine-digit Aegis number. Aegis then keeps a quiet background connection to the " +
                    "relay so messages arrive and calls ring while it is closed; SETTINGS → Online switches that off.",
                color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Relay URL", fontSize = 12.sp) },
                placeholder = { Text("https://aegis-comms.you.workers.dev", fontSize = 12.sp, color = CMuted) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = fieldColors()
            )
            OutlinedTextField(
                value = secret, onValueChange = { secret = it },
                label = { Text("Enrollment secret", fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = fieldColors()
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) },
                label = { Text("Name shown to contacts (optional)", fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors()
            )
            ToggleRow(
                "Listed number",
                if (listed) "Anyone with your number on this relay can add you." else "Unlisted: people can add you only by scanning your code. You can change this later.",
                listed
            ) { listed = it }
            state.error?.let { Text(it, color = CCritical, fontSize = 12.sp) }
            PrimaryButton(if (state.busy) "REGISTERING…" else "REGISTER", enabled = !state.busy && url.isNotBlank() && secret.isNotBlank()) {
                scope.launch { CommsRepository.register(url, secret, name, listed) }
            }
        }

        Card {
            Text("Not a phone number", color = CCaution, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(NOT_A_PHONE, color = CMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }

        Card {
            Text("What is protected", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Every message is encrypted on this phone for one contact with the Olm double ratchet (vodozemac) and " +
                    "sealed again so the relay cannot see who sent it. The relay stores only opaque envelopes, your public " +
                    "keys and a push endpoint. Contacts are pinned by their keys; scanning a code or comparing safety " +
                    "numbers in person proves nobody sits in between. Nothing here uses Google or a phone carrier.",
                color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── Conversation list ────────────────────────────────────────────────────────

@Composable
private fun ThreadListScreen(onOpen: (String) -> Unit, onMyCode: () -> Unit, onInvite: () -> Unit, onSettings: () -> Unit) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val version by CommsRepository.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val threads by produceState(initialValue = emptyList<ChatThread>(), version) {
        value = withContext(Dispatchers.IO) { CommsRepository.threads() }
    }
    var addContact by remember { mutableStateOf(false) }
    val now = rememberTick()

    if (addContact) AddContactDialog(onDismiss = { addContact = false }, onAdded = { addContact = false; onOpen(it) })
    // The coin balance is the relay's figure; it is asked for when the list comes up.
    LaunchedEffect(state.registered) { if (state.registered) CommsRepository.refreshBalance() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Header("COMMS") {
            SmallButton("INVITE", CAccent, onClick = onInvite)
            SmallButton("MY CODE", CInkDim, onClick = onMyCode)
            SmallButton("+ ADD", CClear, onClick = { addContact = true })
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.number?.let { formatAegisNumber(it) } ?: "—", color = CAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("Aegis number · not a phone number · Aegis apps on your relay only", color = CMuted, fontSize = 10.sp)
            }
            SmallButton("SETTINGS", CMuted, onClick = onSettings)
        }
        Spacer(Modifier.height(6.dp))
        // The socket is held open whenever this screen is up, so "Live" is the
        // normal state; what differs is whether anything reaches the phone once
        // Aegis is closed.
        val statusLine = buildString {
            append(if (state.connected) "Live" else "Connecting…")
            append(
                when {
                    state.online -> " · background on"
                    state.pushRegistered -> " · push wake-ups"
                    else -> " · nothing arrives while closed"
                }
            )
            if (!state.listed) append(" · unlisted")
            if (state.balance >= 0L) append(" · ${state.balance} $COIN_SYMBOL")
            if (state.lastSync > 0L) append(" · synced ${agoText(now, state.lastSync)}")
        }
        val statusColour = when {
            !state.connected -> CCaution
            state.online || state.pushRegistered -> CClear
            else -> CCaution
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(statusLine, color = statusColour, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            SmallButton(if (state.busy) "SYNCING" else "SYNC", CMuted, enabled = !state.busy) { scope.launch { CommsRepository.sync() } }
        }
        state.error?.let {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = CCritical, fontSize = 12.sp, modifier = Modifier.weight(1f))
                SmallButton("DISMISS", CMuted) { CommsRepository.clearError() }
            }
        }
        val batteryFree by rememberBatteryUnrestricted()
        if (state.online && !batteryFree) {
            val context = LocalContext.current
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().background(CCaution.copy(alpha = 0.12f), CShape).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Battery saving can cut Aegis off in the background, and then calls do not ring. Allow Aegis to run unrestricted.",
                    color = CInkDim, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                SmallButton("ALLOW", CAccent) { openBatterySettings(context) }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (threads.isEmpty()) {
            Card {
                Text("No contacts yet", color = CInkDim, fontSize = 14.sp)
                Text(
                    "Tap + ADD and scan a contact's Aegis code, or enter their listed Aegis number. Show yours under MY CODE.",
                    color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                items(threads, key = { it.contact.number }) { t -> ThreadRow(t, onClick = { onOpen(t.contact.number) }) }
                item(key = "§footer") { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ThreadRow(t: ChatThread, onClick: () -> Unit) {
    val m = t.lastMessage
    val stripe = when {
        t.contact.keyChanged -> CCritical
        t.unread > 0 -> CAccent
        m?.failed == true -> CCritical
        t.contact.verified -> CClear
        else -> CRule
    }
    Row(Modifier.fillMaxWidth().clip(CShape).background(CPanel).clickable(onClick = onClick).height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(stripe))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    contactLabel(t.contact), color = CInk, fontSize = 14.sp,
                    fontWeight = if (t.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (t.unread > 0) {
                    Text(
                        t.unread.toString(), color = Color(0xFF12161D), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(CAccent, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (m != null) Text(dateTimeFmt.format(Date(m.ts)), color = CMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Text(
                formatAegisNumber(t.contact.number) + when {
                    t.contact.keyChanged -> " · KEYS CHANGED"
                    t.contact.verified -> " · verified"
                    else -> " · unverified"
                },
                color = if (t.contact.keyChanged) CCritical else CMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
            if (m != null) {
                Text(
                    (if (m.direction == Direction.OUT) "You: " else "") + m.preview(),
                    color = if (t.unread > 0) CInkDim else CMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (m.failed) Text(m.error ?: "Not sent", color = CCritical, fontSize = 11.sp)
            }
        }
    }
}

// ── Add contact ──────────────────────────────────────────────────────────────

@Composable
private fun AddContactDialog(onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var number by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scan = rememberCodeScanner { code ->
        if (code == null) { error = "That is not an Aegis code"; return@rememberCodeScanner }
        scope.launch {
            CommsRepository.addContactFromCode(code, name.takeIf { it.isNotBlank() })
                .onSuccess { onAdded(it.number) }
                .onFailure { error = it.message }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CPanel,
        title = { Text("Add a contact", color = CInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Scanning their Aegis code pins their keys directly and marks them verified. Adding by number " +
                        "asks the relay for their keys instead, so they stay unverified until you compare safety numbers.",
                    color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text("Name (optional)", fontSize = 12.sp) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors()
                )
                OutlinedButton(onClick = { error = null; scan() }, shape = CShape, border = BorderStroke(1.dp, CClear), modifier = Modifier.fillMaxWidth()) {
                    Text("SCAN THEIR CODE", color = CClear, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                }
                Text("or, if their number is listed", color = CMuted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                OutlinedTextField(
                    value = number, onValueChange = { number = it.filter { ch -> ch.isDigit() || ch == ' ' }.take(11) },
                    label = { Text("Aegis number (9 digits)", fontSize = 12.sp) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors()
                )
                Text(NOT_A_PHONE, color = CMuted, fontSize = 10.sp, lineHeight = 14.sp)
                error?.let { Text(it, color = CCritical, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.busy && parseAegisNumber(number) != null,
                onClick = {
                    error = null
                    scope.launch {
                        CommsRepository.addContactByNumber(number, name)
                            .onSuccess { onAdded(it.number) }
                            .onFailure { error = it.message }
                    }
                }
            ) { Text(if (state.busy) "LOOKING UP…" else "ADD BY NUMBER", color = CAccent, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = CMuted, letterSpacing = 1.sp) } }
    )
}

// ── Conversation ─────────────────────────────────────────────────────────────

@Composable
private fun ThreadScreen(peer: String, onBack: () -> Unit, onVerify: () -> Unit) {
    val context = LocalContext.current
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val version by CommsRepository.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val contact by produceState<Contact?>(initialValue = null, version, peer) {
        value = withContext(Dispatchers.IO) { CommsRepository.contact(peer) }
    }
    val messages by produceState(initialValue = emptyList<ChatMessage>(), version, peer) {
        value = withContext(Dispatchers.IO) { CommsRepository.messages(peer) }
    }
    var draft by rememberSaveable(peer) { mutableStateOf("") }
    var sendError by remember(peer) { mutableStateOf<String?>(null) }
    var paying by remember(peer) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Voicemails in this conversation play through one player, stopped when the screen goes.
    val player = remember { VoicemailPlayer(context) }
    var playing by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player) { onDispose { player.stop() } }

    // A photo or video picked (or taken) and made ready to send, waiting for SEND;
    // the text typed alongside goes with it as the caption.
    val mediaProgress by CommsRepository.mediaProgress.collectAsStateWithLifecycle()
    var attachment by remember(peer) { mutableStateOf<CommsMedia.Prepared?>(null) }
    var preparing by remember(peer) { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ChatMessage?>(null) }
    // Where the camera writes; kept across the trip to the camera app.
    var captureUri by rememberSaveable { mutableStateOf<String?>(null) }
    fun prepare(uri: Uri, video: Boolean?, deleteAfter: File? = null) {
        preparing = true
        sendError = null
        scope.launch {
            val isVideo = video ?: (context.contentResolver.getType(uri)?.startsWith("video/") == true)
            val result = withContext(Dispatchers.IO) {
                runCatching { if (isVideo) CommsMedia.prepareVideo(context, uri) else CommsMedia.prepareImage(context, uri) }
            }
            deleteAfter?.let { runCatching { it.delete() } }
            preparing = false
            result.onSuccess { attachment = it }
                .onFailure { sendError = it.message ?: "That file could not be read" }
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) prepare(uri, null)
    }
    fun captureFile(extension: String): Pair<File, Uri> {
        val f = File(File(context.cacheDir, "camera").also { it.mkdirs() }, "capture_${System.currentTimeMillis()}.$extension")
        return f to FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }
    fun capturedFile(): File? = captureUri?.let { File(File(context.cacheDir, "camera"), Uri.parse(it).lastPathSegment ?: return null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = captureUri?.let { Uri.parse(it) }
        val file = capturedFile()
        captureUri = null
        if (ok && uri != null) prepare(uri, video = false, deleteAfter = file) else file?.delete()
    }
    val recordVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val uri = captureUri?.let { Uri.parse(it) }
        val file = capturedFile()
        captureUri = null
        if (ok && uri != null) prepare(uri, video = true, deleteAfter = file) else file?.delete()
    }
    var cameraWanted by remember { mutableStateOf<Boolean?>(null) } // true: photo, false: video
    fun openCamera(video: Boolean) {
        val (f, uri) = captureFile(if (video) "mp4" else "jpg")
        runCatching { f.parentFile?.listFiles()?.forEach { old -> if (old != f) old.delete() } }
        captureUri = uri.toString()
        if (video) recordVideo.launch(uri) else takePhoto.launch(uri)
    }
    // Aegis declares the camera permission (for scanning codes), so the camera
    // app refuses to hand a picture back until it is granted.
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val video = cameraWanted ?: return@rememberLauncherForActivityResult
        cameraWanted = null
        if (granted) openCamera(video) else sendError = "Taking a ${if (video) "video" else "photo"} needs the camera. Allow it under Permissions."
    }
    fun camera(video: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera(video)
        else { cameraWanted = video; askCamera.launch(Manifest.permission.CAMERA) }
    }
    if (attachMenu) {
        AttachMenu(
            onDismiss = { attachMenu = false },
            onGallery = { attachMenu = false; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onPhoto = { attachMenu = false; camera(video = false) },
            onVideo = { attachMenu = false; camera(video = true) }
        )
    }
    viewing?.let { MediaViewer(it, onDismiss = { viewing = null }) }

    // While this conversation is on screen and the app is in the foreground, its
    // messages are read on arrival and raise no notification. The composition
    // survives Home and the screen turning off, so the lifecycle decides, not
    // the composition: otherwise a message that arrived to a pocketed phone
    // would be receipted as read.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(peer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    CommsRepository.openPeer = peer
                    CommsNotifications.cancel(context, peer)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    if (CommsRepository.openPeer == peer) CommsRepository.openPeer = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (resumed) {
            CommsRepository.openPeer = peer
            CommsNotifications.cancel(context, peer)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (CommsRepository.openPeer == peer) CommsRepository.openPeer = null
        }
    }
    LaunchedEffect(peer, version, resumed) { if (resumed) CommsRepository.markRead(peer) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    val c = contact
    val placeCall = rememberMicrophoneGate { c?.let { CallManager.place(it) } }
    if (paying && c != null) PayDialog(c, onDismiss = { paying = false })
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Header(c?.let { contactLabel(it) } ?: formatAegisNumber(peer), onBack = onBack) {
            val (label, colour) = when {
                c == null -> "" to CMuted
                c.keyChanged -> "KEYS CHANGED" to CCritical
                c.verified -> "VERIFIED" to CClear
                else -> "UNVERIFIED" to CCaution
            }
            if (label.isNotEmpty()) SmallButton(label, colour, onClick = onVerify)
            SmallButton("PAY", CAccent, enabled = c != null && !c.keyChanged) { paying = true }
            SmallButton("CALL", CClear, enabled = c != null && !c.keyChanged, onClick = placeCall)
        }
        Text(
            "${formatAegisNumber(peer)} · end-to-end encrypted · Aegis number, not a phone number",
            color = CMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
        if (c?.keyChanged == true) {
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth().background(CCritical.copy(alpha = 0.12f), CShape).padding(10.dp)) {
                Text("This contact's keys changed", color = CCritical, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("They may have reinstalled Aegis, or someone may be in between. Scan their code or compare safety numbers before continuing.", color = CInkDim, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (messages.isEmpty()) {
                item(key = "§empty") {
                    Text(
                        "No messages yet. The first one you send starts an encrypted session with ${c?.let { contactLabel(it) } ?: "this contact"}.",
                        color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
                    )
                }
            }
            items(messages, key = { it.id }) { m ->
                MessageBubble(
                    m,
                    onRetry = { scope.launch { CommsRepository.retry(m.id) } },
                    playing = playing == m.id,
                    progress = mediaProgress[m.id],
                    onOpen = { viewing = m },
                    onPlay = {
                        if (playing == m.id) {
                            player.stop()
                            playing = null
                        } else {
                            val audio = VoicemailBody.decode(m.body)
                            if (audio != null && player.play(m.id, audio.audioB64) { playing = null }) playing = m.id
                            else Toast.makeText(context, "This voicemail cannot be played", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            item(key = "§footer") { Spacer(Modifier.height(4.dp)) }
        }

        sendError?.let { Text(it, color = CCritical, fontSize = 12.sp) }
        val pending = attachment
        if (pending != null || preparing) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).background(CPanel, CShape).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val thumb = remember(pending) { pending?.thumbB64?.takeIf { it.isNotBlank() }?.let { CommsMedia.decodeB64(it, 160)?.asImageBitmap() } }
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(CPanelHi), contentAlignment = Alignment.Center) {
                    if (thumb != null) Image(thumb, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else if (preparing) CircularProgressIndicator(color = CAccent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    else PictureGlyph(CMuted, Modifier.size(26.dp))
                    if (pending?.isVideo == true) Text("▶", color = CInk, fontSize = 18.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when {
                            pending == null -> "Preparing…"
                            pending.isVideo -> "Video · ${durationLabel(pending.durationMs)}"
                            else -> "Photo · ${pending.width}×${pending.height}"
                        },
                        color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (pending == null) "Reading and compressing the file"
                        else "${sizeLabel(pending.bytes.size.toLong())} · ${(pending.bytes.size + CommsWire.MEDIA_CHUNK_BYTES - 1) / CommsWire.MEDIA_CHUNK_BYTES} encrypted envelope(s) · type a caption below, or none",
                        color = CMuted, fontSize = 11.sp, lineHeight = 14.sp
                    )
                }
                SmallButton("REMOVE", CMuted, enabled = !preparing) { attachment = null }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { attachMenu = true },
                enabled = c != null && !c.keyChanged && attachment == null && !preparing,
                shape = CShape, border = BorderStroke(1.dp, CRule), contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(56.dp)
            ) { PictureGlyph(if (c != null && attachment == null && !preparing) CAccent else CMuted, Modifier.size(24.dp)) }
            OutlinedTextField(
                value = draft, onValueChange = { draft = it.take(4000) },
                placeholder = { Text(if (attachment != null) "Caption (optional)" else "Encrypted message", color = CMuted, fontSize = 13.sp) },
                modifier = Modifier.weight(1f), maxLines = 5, colors = fieldColors()
            )
            Button(
                onClick = {
                    val text = draft.trim()
                    val media = attachment
                    if (text.isEmpty() && media == null) return@Button
                    sendError = null
                    draft = ""
                    attachment = null
                    scope.launch {
                        if (media != null) CommsRepository.sendMedia(peer, media, text).onFailure { sendError = it.message ?: "Not sent" }
                        else CommsRepository.send(peer, text).onFailure { sendError = it.message ?: "Not sent" }
                    }
                },
                enabled = (draft.isNotBlank() || attachment != null) && c != null && !preparing,
                colors = ButtonDefaults.buttonColors(containerColor = CAccent, contentColor = Color(0xFF12161D), disabledContainerColor = CPanelHi, disabledContentColor = CMuted),
                shape = CShape, modifier = Modifier.height(56.dp)
            ) { Text("SEND", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        }
        when {
            !state.connected -> Text("Connecting to the relay… messages you send are queued until it is back.", color = CCaution, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp))
            !state.online && !state.pushRegistered -> Text("Live while Aegis is open. Nothing arrives, and calls cannot ring, once it is closed: turn on Online or push in SETTINGS.", color = CCaution, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun MessageBubble(
    m: ChatMessage,
    onRetry: () -> Unit,
    playing: Boolean = false,
    onPlay: () -> Unit = {},
    /** (chunks sent, chunks in all) while an outgoing photo or video is on its way. */
    progress: Pair<Int, Int>? = null,
    onOpen: () -> Unit = {}
) {
    val mine = m.direction == Direction.OUT
    if (m.isMedia) {
        MediaBubble(m, mine, progress, onOpen, onRetry)
        return
    }
    if (m.status == STATUS_CALL || m.status == STATUS_PAYMENT) {
        // A call or payment record: one quiet centred line in the conversation.
        val missed = m.status == STATUS_CALL && m.body.startsWith("Missed")
        val colour = when {
            missed -> CCritical
            m.status == STATUS_PAYMENT -> CClear
            else -> CMuted
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (mine) "↗ " else "↙ ") + m.body + " · " + timeFmt.format(Date(m.ts)),
                color = colour, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.background(CPanel, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        return
    }
    if (m.status == STATUS_VOICEMAIL) {
        val duration = VoicemailBody.decode(m.body)?.durationMs ?: 0L
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Row(
                Modifier
                    .widthIn(max = 300.dp)
                    .background(if (mine) CPanelHi else CPanel, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(containerColor = if (playing) CCritical else CClear, contentColor = Color(0xFF12161D)),
                    shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp)
                ) { Text(if (playing) "■ STOP" else "▶ PLAY", fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Voicemail · ${durationLabel(duration)}", color = CInk, fontSize = 13.sp)
                    Text(timeFmt.format(Date(m.ts)), color = CMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(if (mine) CPanelHi else CPanel, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(m.body, color = CInk, fontSize = 14.sp, lineHeight = 19.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(timeFmt.format(Date(m.ts)), color = CMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                if (mine) {
                    val (glyph, colour) = when (m.status) {
                        "queued" -> "…" to CMuted
                        "sent" -> "✓" to CMuted
                        "delivered" -> "✓✓" to CMuted
                        "read" -> "✓✓" to CClear
                        "failed" -> "!" to CCritical
                        else -> "" to CMuted
                    }
                    Text(glyph, color = colour, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (m.failed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.error ?: "Not sent", color = CCritical, fontSize = 11.sp, modifier = Modifier.widthIn(max = 240.dp))
                SmallButton("RETRY", CAccent, onClick = onRetry)
            }
        }
    }
}

// ── Photos and videos ────────────────────────────────────────────────────────

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    else -> "${(bytes + 1023) / 1024} KB"
}

/** The glyph a message's delivery status shows after its time, for the sender. */
private fun statusGlyph(status: String): Pair<String, Color> = when (status) {
    "queued" -> "…" to CMuted
    "sent" -> "✓" to CMuted
    "delivered" -> "✓✓" to CMuted
    "read" -> "✓✓" to CClear
    "failed" -> "!" to CCritical
    else -> "" to CMuted
}

/**
 * A photo or video in the conversation: the picture itself (the thumbnail that
 * came in the first envelope until the whole file is here, then the file),
 * its caption, and how far it has got. A tap opens it full-screen once it is
 * complete.
 */
@Composable
private fun MediaBubble(m: ChatMessage, mine: Boolean, progress: Pair<Int, Int>?, onOpen: () -> Unit, onRetry: () -> Unit) {
    val media = remember(m.body) { MediaBody.decode(m.body) }
    val receiving = m.status == STATUS_RECEIVING
    val complete = !receiving && m.status != "failed" || mine
    val thumb = remember(m.id, media?.thumbB64) { media?.thumbB64?.takeIf { it.isNotBlank() }?.let { CommsMedia.decodeB64(it, 480)?.asImageBitmap() } }
    // A photo that is here in full is shown from the file, sharper than its thumbnail.
    val full by produceState<ImageBitmap?>(initialValue = null, m.id, m.status) {
        if (media != null && !media.isVideo && (mine || !receiving)) {
            value = withContext(Dispatchers.IO) { CommsRepository.mediaBytes(m.id)?.let { CommsMedia.decode(it, 900)?.asImageBitmap() } }
        }
    }
    val picture = full ?: thumb
    val ratio = if (media != null && media.width > 0 && media.height > 0) media.width.toFloat() / media.height else 4f / 3f
    val width = 240.dp
    val height = (width / ratio.coerceIn(0.6f, 2.2f))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier
                .widthIn(max = 264.dp)
                .background(if (mine) CPanelHi else CPanel, RoundedCornerShape(10.dp))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                Modifier
                    .width(width).height(height)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CGround)
                    .clickable(enabled = complete && media != null, onClick = onOpen),
                contentAlignment = Alignment.Center
            ) {
                if (picture != null) {
                    Image(
                        picture, contentDescription = media?.label() ?: "Attachment",
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                    )
                } else {
                    PictureGlyph(CMuted, Modifier.size(40.dp))
                }
                if (media?.isVideo == true) {
                    Box(Modifier.size(52.dp).background(Color(0xAA0E1116), RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
                        Text("▶", color = CInk, fontSize = 22.sp)
                    }
                    Text(
                        durationLabel(media.durationMs), color = CInk, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xAA0E1116), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                if (receiving || progress != null) {
                    Column(
                        Modifier.fillMaxSize().background(Color(0x880E1116)),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = CAccent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (progress != null) "Sending ${progress.first}/${progress.second}" else "Receiving…",
                            color = CInk, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            if (media != null && media.caption.isNotBlank()) {
                Text(media.caption, color = CInk, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    (media?.let { if (it.isVideo) "Video" else "Photo" } ?: "Attachment") + (media?.let { " · ${sizeLabel(it.size)}" } ?: "") + " · " + timeFmt.format(Date(m.ts)),
                    color = CMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
                if (mine) {
                    val (glyph, colour) = statusGlyph(m.status)
                    Text(glyph, color = colour, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (m.failed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.error ?: "Not sent", color = CCritical, fontSize = 11.sp, modifier = Modifier.widthIn(max = 240.dp))
                if (mine) SmallButton("RETRY", CAccent, onClick = onRetry)
            }
        }
    }
}

/** Where an attachment comes from. */
@Composable
private fun AttachMenu(onDismiss: () -> Unit, onGallery: () -> Unit, onPhoto: () -> Unit, onVideo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CPanel,
        title = { Text("Send a photo or video", color = CInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Photos are resized to at most ${CommsMedia.MAX_IMAGE_EDGE} px and sent as JPEG; videos go as they are, up to " +
                        "${CommsMedia.MAX_BYTES / (1024 * 1024)} MB. Either travels end-to-end encrypted in pieces the relay cannot read.",
                    color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
                )
                OutlinedButton(onClick = onGallery, shape = CShape, border = BorderStroke(1.dp, CAccent), modifier = Modifier.fillMaxWidth()) {
                    Text("CHOOSE FROM GALLERY", color = CAccent, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onPhoto, shape = CShape, border = BorderStroke(1.dp, CClear), modifier = Modifier.fillMaxWidth()) {
                    Text("TAKE A PHOTO", color = CClear, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onVideo, shape = CShape, border = BorderStroke(1.dp, CClear), modifier = Modifier.fillMaxWidth()) {
                    Text("RECORD A VIDEO", color = CClear, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = CMuted, letterSpacing = 1.sp) } }
    )
}

/**
 * A photo or video full-screen. The bytes are decrypted from the message's
 * file; a video is written to the cache only for as long as this is open,
 * since VideoView plays from a path.
 */
@Composable
private fun MediaViewer(m: ChatMessage, onDismiss: () -> Unit) {
    val media = remember(m.body) { MediaBody.decode(m.body) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xF20A0D12))) {
            if (media == null) {
                Text("This attachment cannot be shown", color = CCritical, modifier = Modifier.align(Alignment.Center))
            } else if (media.isVideo) {
                var file by remember { mutableStateOf<File?>(null) }
                var missing by remember { mutableStateOf(false) }
                LaunchedEffect(m.id) {
                    var f: File? = null
                    try {
                        // Assigned inside the IO block: a withContext cancelled
                        // mid-copy still runs the block to its end but drops
                        // the result, and the finished copy would be orphaned.
                        withContext(Dispatchers.IO) { f = CommsMedia.tempCopy(m.id, "mp4") }
                        if (f == null) missing = true else file = f
                    } finally {
                        // Cancelled before `file` was assigned (the viewer closed
                        // mid-copy): the dispose below would see null and miss it.
                        if (file == null) CommsMedia.discardTemp(f)
                    }
                }
                DisposableEffect(m.id) { onDispose { CommsMedia.discardTemp(file) } }
                val f = file
                if (f != null) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                                setOnPreparedListener { it.isLooping = false; start() }
                                setOnErrorListener { _, _, _ -> Toast.makeText(ctx, "This video cannot be played", Toast.LENGTH_SHORT).show(); true }
                                setVideoPath(f.absolutePath)
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(vertical = 48.dp)
                    )
                } else if (missing) {
                    Text("The video is no longer on this phone", color = CCritical, modifier = Modifier.align(Alignment.Center))
                } else {
                    CircularProgressIndicator(color = CAccent, modifier = Modifier.align(Alignment.Center))
                }
            } else {
                val image by produceState<ImageBitmap?>(initialValue = null, m.id) {
                    value = withContext(Dispatchers.IO) {
                        (CommsRepository.mediaBytes(m.id)?.let { CommsMedia.decode(it, 2048) } ?: CommsMedia.decodeB64(media.thumbB64, 480))?.asImageBitmap()
                    }
                }
                val bitmap = image
                if (bitmap != null) {
                    Image(bitmap, contentDescription = media.label(), modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss), contentScale = ContentScale.Fit)
                } else {
                    CircularProgressIndicator(color = CAccent, modifier = Modifier.align(Alignment.Center))
                }
            }
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    media?.let { (if (it.isVideo) "Video · ${durationLabel(it.durationMs)}" else "Photo · ${it.width}×${it.height}") + " · ${sizeLabel(it.size)}" } ?: "",
                    color = CInkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
                )
                SmallButton("CLOSE", CAccent, onClick = onDismiss)
            }
            if (media != null && media.caption.isNotBlank()) {
                Text(
                    media.caption, color = CInk, fontSize = 14.sp, lineHeight = 19.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth().background(Color(0xB00E1116)).padding(16.dp)
                )
            }
        }
    }
}

/** A picture drawn in place: a framed landscape with a sun, the attach button's icon. */
@Composable
private fun PictureGlyph(colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawRoundRect(colour, topLeft = Offset(stroke / 2, stroke / 2), size = Size(w - stroke, h - stroke), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = Stroke(stroke))
        drawCircle(colour, radius = w * 0.09f, center = Offset(w * 0.34f, h * 0.34f))
        val hills = Path().apply {
            moveTo(w * 0.16f, h * 0.78f)
            lineTo(w * 0.42f, h * 0.5f)
            lineTo(w * 0.56f, h * 0.64f)
            lineTo(w * 0.66f, h * 0.55f)
            lineTo(w * 0.84f, h * 0.78f)
            close()
        }
        drawPath(hills, colour)
    }
}

// ── AegisCoin ────────────────────────────────────────────────────────────────

/**
 * Pays a contact. The amount is a whole number of coins, the note goes to the
 * contact inside the encrypted envelope and never to the relay. The balance
 * shown is the relay's, refreshed when the dialog opens.
 */
@Composable
private fun PayDialog(contact: Contact, onDismiss: () -> Unit) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { CommsRepository.refreshBalance() }
    val coins = amount.toLongOrNull()?.takeIf { it > 0L }
    val canPay = coins != null && !sending && (state.balance < 0L || coins <= state.balance)

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        containerColor = CPanel,
        title = { Text("Pay ${contactLabel(contact)}", color = CInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (state.balance >= 0L) "Balance: ${state.balance} $COIN_SYMBOL on this relay" else "Balance: asking the relay…",
                    color = if (coins != null && state.balance >= 0L && coins > state.balance) CCritical else CInkDim,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace
                )
                OutlinedTextField(
                    value = amount, onValueChange = { v -> amount = v.filter { it.isDigit() }.take(7); error = null },
                    label = { Text("Amount ($COIN_SYMBOL)") }, singleLine = true, enabled = !sending,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors()
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(140) },
                    label = { Text("Note (optional, encrypted)") }, maxLines = 3, enabled = !sending, colors = fieldColors()
                )
                Text(
                    "AegisCoin is this relay's own currency. The relay moves the coins on your signed request; the note goes to ${contactLabel(contact)} end-to-end encrypted.",
                    color = CMuted, fontSize = 11.sp, lineHeight = 15.sp
                )
                error?.let { Text(it, color = CCritical, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canPay,
                onClick = {
                    val value = coins ?: return@TextButton
                    sending = true
                    error = null
                    scope.launch {
                        CommsRepository.sendPayment(contact, value, note)
                            .onSuccess {
                                Toast.makeText(context, "Paid $value $COIN_SYMBOL to ${contactLabel(contact)}", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                            .onFailure {
                                sending = false
                                error = it.message ?: "Payment failed"
                            }
                    }
                }
            ) { Text(if (sending) "PAYING…" else "PAY", color = if (canPay) CAccent else CMuted, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("CANCEL", color = CMuted, letterSpacing = 1.sp) } }
    )
}

// ── In a call ────────────────────────────────────────────────────────────────

@Composable
internal fun InCallScreen(call: ActiveCall, modifier: Modifier = Modifier) {
    val now = rememberTick(1_000L)
    val accept = rememberMicrophoneGate { CallManager.accept() }
    val (title, colour) = when (call.phase) {
        CallPhase.INCOMING -> "INCOMING CALL" to CClear
        CallPhase.DIALING -> if (call.ringing) "RINGING" to CClear else "CALLING" to CMuted
        CallPhase.CONNECTING -> "CONNECTING" to CMuted
        CallPhase.CONNECTED -> "ENCRYPTED CALL" to CClear
        CallPhase.RECONNECTING -> "RECONNECTING" to CCaution
        CallPhase.ENDED -> "CALL ENDED" to CMuted
    }
    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = colour, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        Text(contactLabel(call.peer), color = CInk, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            formatAegisNumber(call.peer.number) + when {
                call.peer.keyChanged -> " · KEYS CHANGED"
                call.peer.verified -> " · verified"
                else -> " · unverified"
            },
            color = if (call.peer.verified && !call.peer.keyChanged) CMuted else CCaution,
            fontSize = 12.sp, fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                call.phase == CallPhase.ENDED -> call.endReason?.replaceFirstChar { it.uppercase() } ?: "Ended"
                call.connectedAt > 0L -> CallManager.durationText(now - call.connectedAt)
                call.phase == CallPhase.INCOMING -> "Aegis call · not a phone call"
                call.phase == CallPhase.DIALING && call.ringing -> "Their phone is ringing"
                call.phase == CallPhase.DIALING -> "Reaching their phone"
                call.phase == CallPhase.RECONNECTING -> "The connection dropped; bringing it back"
                else -> "Connecting the encrypted audio"
            },
            color = if (call.phase == CallPhase.ENDED && call.connectedAt == 0L) CCaution else CInkDim,
            fontSize = 14.sp, fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(48.dp))
        when (call.phase) {
            CallPhase.INCOMING -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                CallButton("DECLINE", CCritical) { CallManager.reject() }
                CallButton("ACCEPT", CClear) { accept() }
            }
            CallPhase.ENDED -> Unit
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ToggleChip(if (call.muted) "UNMUTE" else "MUTE", call.muted) { CallManager.toggleMute() }
                    ToggleChip("SPEAKER", call.speaker) { CallManager.toggleSpeaker() }
                }
                CallButton("HANG UP", CCritical) { CallManager.hangUp() }
            }
        }
        Spacer(Modifier.height(40.dp))
        Text(
            buildString {
                append("End-to-end encrypted: the keys for the audio were exchanged inside your encrypted session, so the relay cannot listen or step in.")
                if (call.relayed) append(" Audio is being forwarded by a TURN relay, which carries it but cannot decrypt it.")
            },
            color = CMuted, fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CallButton(label: String, colour: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = colour, contentColor = Color(0xFF12161D)),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.height(56.dp).widthIn(min = 140.dp)
    ) { Text(label, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (on) CAccent else CRule),
        colors = ButtonDefaults.textButtonColors(containerColor = if (on) CAccent.copy(alpha = 0.15f) else CPanel)
    ) { Text(label, color = if (on) CAccent else CInkDim, fontSize = 12.sp, letterSpacing = 1.sp) }
}

// ── Verify a contact ─────────────────────────────────────────────────────────

@Composable
private fun VerifyScreen(peer: String, onBack: () -> Unit, onDeleted: () -> Unit) {
    val version by CommsRepository.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    // Loading and "no such contact" are different states: the first render has
    // not looked the contact up yet and must not navigate away.
    val lookup by produceState<Pair<Boolean, Contact?>>(initialValue = false to null, version, peer) {
        value = true to withContext(Dispatchers.IO) { CommsRepository.contact(peer) }
    }
    val (loaded, contact) = lookup
    if (!loaded) return
    val c = contact ?: run {
        LaunchedEffect(Unit) { onDeleted() }
        return
    }
    val safety = remember(c.ed25519) { CommsRepository.safetyNumber(c) }
    var name by rememberSaveable(c.number) { mutableStateOf(c.name) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scan = rememberCodeScanner { code ->
        when {
            code == null -> error = "That is not an Aegis code"
            code.number != c.number -> error = "That code belongs to ${formatAegisNumber(code.number)}, not this contact"
            else -> scope.launch {
                CommsRepository.addContactFromCode(code, name).onFailure { error = it.message }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = CPanel,
            title = { Text("Remove ${contactLabel(c)}?", color = CInk, fontWeight = FontWeight.Bold) },
            text = { Text("Their keys, the encrypted session and every message with them are deleted from this phone.", color = CInkDim, fontSize = 13.sp, lineHeight = 18.sp) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; scope.launch { CommsRepository.deleteContact(c.number); onDeleted() } }) {
                    Text("REMOVE", color = CCritical, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL", color = CMuted, letterSpacing = 1.sp) } }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Header(contactLabel(c), onBack = onBack)
        Text("${formatAegisNumber(c.number)} · Aegis number, not a phone number", color = CMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

        Card {
            val (label, colour) = when {
                c.keyChanged -> "KEYS CHANGED — verify again" to CCritical
                c.verified -> "VERIFIED" to CClear
                else -> "UNVERIFIED" to CCaution
            }
            Text(label, color = colour, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(
                if (c.verified && !c.keyChanged) "You scanned this contact's code or compared safety numbers, so their keys came from them, not from the relay."
                else "Their keys came from the relay (or from their first message). A relay that lied could put itself in between. Verifying rules that out.",
                color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
            )
        }

        Card {
            Text("Safety number", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Both phones show the same 60 digits for this pair. Compare them in person or over a call you trust.", color = CMuted, fontSize = 12.sp, lineHeight = 17.sp)
            if (safety != null) {
                val groups = safety.split(" ")
                Column(Modifier.fillMaxWidth().background(CGround, CShape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    groups.chunked(4).forEach { row ->
                        Text(row.joinToString("   "), color = CInk, fontSize = 15.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("COPY", CInkDim) { clipboard.setText(AnnotatedString(safety)) }
                    if (!c.verified || c.keyChanged) SmallButton("THEY MATCH — MARK VERIFIED", CClear) { scope.launch { CommsRepository.markVerified(c.number) } }
                }
            } else {
                Text("Safety number unavailable: this device has no identity", color = CCritical, fontSize = 12.sp)
            }
        }

        Card {
            Text("Or scan their code", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Their MY CODE screen carries their keys. A matching scan verifies them in one step.", color = CMuted, fontSize = 12.sp, lineHeight = 17.sp)
            OutlinedButton(onClick = { error = null; scan() }, shape = CShape, border = BorderStroke(1.dp, CClear), modifier = Modifier.fillMaxWidth()) {
                Text("SCAN THEIR CODE", color = CClear, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            }
            error?.let { Text(it, color = CCritical, fontSize = 12.sp) }
        }

        Card {
            Text("Name", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors())
                SmallButton("SAVE", CAccent, enabled = name.trim() != c.name) { scope.launch { CommsRepository.renameContact(c.number, name) } }
            }
            Text("Identity key ${c.ed25519.take(16)}…", color = CMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }

        TextButton(onClick = { confirmDelete = true }, contentPadding = PaddingValues(0.dp)) {
            Text("REMOVE CONTACT", color = CCritical, fontSize = 11.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── My code ──────────────────────────────────────────────────────────────────

/**
 * Makes a one-time invite and shows it three ways: a QR code for someone
 * standing next to you (they scan it in Aegis setup), a link to send them
 * (it opens a page with the download and an OPEN IN AEGIS button), and the
 * share sheet.
 */
@Composable
private fun InviteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var made by remember { mutableStateOf<Pair<PairingCode, Long>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val create: () -> Unit = {
        busy = true
        error = null
        scope.launch {
            CommsRepository.createInvite()
                .onSuccess { made = it }
                .onFailure { error = it.message ?: "Could not make an invite" }
            busy = false
        }
    }
    val invite = made
    val bitmap = remember(invite) { invite?.first?.let { qrBitmap(it.encode(), 720) } }
    val until = invite?.second?.let { SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(it)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Header("INVITE", onBack = onBack)
        Card {
            Text("Invite someone to Aegis", color = CInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "An invite lets one person get an Aegis number on your relay without the relay URL or its enrollment secret. " +
                    "It works once and expires after seven days. They start with you as a verified contact, and you get a message when they join.",
                color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
            )
            if (invite == null) {
                PrimaryButton(if (busy) "MAKING INVITE…" else "MAKE AN INVITE", enabled = !busy, onClick = create)
            }
            error?.let { Text(it, color = CCritical, fontSize = 12.sp) }
        }
        if (invite != null) {
            val link = invite.first.inviteLink()
            Card {
                Text("Next to you: let them scan this", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("In Aegis on their phone: COMMS → SCAN INVITE.", color = CMuted, fontSize = 12.sp)
                if (bitmap != null) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Image(bitmap.asImageBitmap(), contentDescription = "Aegis invite code", modifier = Modifier.size(260.dp).clip(RoundedCornerShape(6.dp)))
                    }
                }
            }
            Card {
                Text("Far away: send them the link", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "The link opens a page with the Aegis download and an OPEN IN AEGIS button. Anyone who gets the link can use it, " +
                        "so send it only to the person you mean. Works once, until $until.",
                    color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("SHARE LINK") {
                        val text = "Join me on Aegis for end-to-end encrypted messages and calls: $link\n" +
                            "The invite works once, until $until."
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        runCatching { context.startActivity(Intent.createChooser(send, "Send invite").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("COPY LINK", CInkDim) { clipboard.setText(AnnotatedString(link)) }
                    SmallButton("NEW INVITE", CInkDim, enabled = !busy) { made = null; create() }
                }
            }
        }
        Card {
            Text("Not a phone number", color = CCaution, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(NOT_A_PHONE, color = CMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** An invite link opened on a phone that already has a number: offer to add the inviter instead. */
@Composable
private fun AddInviterDialog(code: PairingCode, onDone: () -> Unit, onAdded: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val mine = CommsRepository.state.collectAsStateWithLifecycle().value.number == code.number
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = CPanel,
        title = { Text(if (mine) "Your own invite" else "Add ${code.name.ifBlank { formatAegisNumber(code.number) }}?", color = CInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (mine) "This invite is for someone who does not have Aegis yet. Send them the link."
                    else "This phone already has an Aegis number, so the invite is not needed. You can add " +
                        "${formatAegisNumber(code.number)} as a verified contact from it; the invite stays unused.",
                    color = CInkDim, fontSize = 13.sp, lineHeight = 18.sp
                )
                error?.let { Text(it, color = CCritical, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            if (!mine) TextButton(onClick = {
                scope.launch {
                    CommsRepository.addContactFromCode(code.copy(invite = null))
                        .onSuccess { onDone(); onAdded(it.number) }
                        .onFailure { error = it.message }
                }
            }) { Text("ADD CONTACT", color = CAccent, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(if (mine) "OK" else "CANCEL", color = CMuted, letterSpacing = 1.sp) } }
    )
}

@Composable
private fun MyCodeScreen(onBack: () -> Unit) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val code = remember(state.number, state.displayName) { CommsRepository.pairingCode() }
    val bitmap = remember(code) { code?.let { qrBitmap(it.encode(), 720) } }
    val myKey = remember(state.number) { CommsRepository.myEd25519() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Header("MY CODE", onBack = onBack)
        Card {
            Text(state.number?.let { formatAegisNumber(it) } ?: "—", color = CAccent, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (state.displayName.isNotBlank()) Text(state.displayName, color = CInkDim, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text("Aegis number · ${if (state.listed) "listed" else "unlisted"}", color = CMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (bitmap != null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Image(bitmap.asImageBitmap(), contentDescription = "Your Aegis pairing code", modifier = Modifier.size(260.dp).clip(RoundedCornerShape(6.dp)))
                }
            }
            Text(
                "Let a contact scan this to add you with your keys pinned and verified. The code holds your relay, number and public keys only.",
                color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.number?.let { n -> SmallButton("COPY NUMBER", CInkDim) { clipboard.setText(AnnotatedString(formatAegisNumber(n))) } }
                code?.let { c -> SmallButton("COPY CODE TEXT", CInkDim) { clipboard.setText(AnnotatedString(c.encode())) } }
            }
        }
        Card {
            Text("Not a phone number", color = CCaution, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(NOT_A_PHONE, color = CMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (myKey != null) {
            Card {
                Text("Identity key", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(myKey, color = CInkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("Generated on this phone; the private half never leaves it. A reinstall makes a new identity and a new number.", color = CMuted, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── Settings ─────────────────────────────────────────────────────────────────

@Composable
private fun CommsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(state.displayName) { mutableStateOf(state.displayName) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var notificationsGranted by remember { mutableStateOf(CommsNotifications.canPost(context)) }
    var callsChannelOk by remember { mutableStateOf(CommsNotifications.callsChannelOk(context)) }
    var microphoneGranted by remember { mutableStateOf(hasMicrophone(context)) }
    // Once refused twice, Android answers the request with "denied" without
    // showing anything; the owner is then sent to the settings page instead.
    fun blocked(permission: String) =
        context.findActivity()?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } == true
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsGranted = granted
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && blocked(Manifest.permission.POST_NOTIFICATIONS)) {
            CommsNotifications.openAppNotificationSettings(context)
        }
    }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneGranted = granted
        if (!granted && blocked(Manifest.permission.RECORD_AUDIO)) openAppDetails(context)
    }
    // Re-check after coming back from the system settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsGranted = CommsNotifications.canPost(context)
                callsChannelOk = CommsNotifications.callsChannelOk(context)
                microphoneGranted = hasMicrophone(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val allowNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Before API 33 there is no runtime permission; the switch lives in system settings.
            CommsNotifications.openAppNotificationSettings(context)
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            containerColor = CPanel,
            title = { Text("Give up this Aegis number?", color = CInk, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "The relay deletes your mailbox and keys, and this phone deletes its identity, contacts and messages. " +
                        "Registering again gives a different number; contacts will have to add you afresh.",
                    color = CInkDim, fontSize = 13.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmUnpair = false; scope.launch { CommsRepository.unpair() } }) {
                    Text("DELETE EVERYTHING", color = CCritical, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("CANCEL", color = CMuted, letterSpacing = 1.sp) } }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Header("COMMS SETTINGS", onBack = onBack)
        Text("${state.number?.let { formatAegisNumber(it) } ?: "—"} on ${state.relayUrl ?: "—"}", color = CMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

        Card {
            Text("Delivery", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            ToggleRow(
                "Online",
                "Keeps a connection to the relay open in the background, with a quiet notification, so messages arrive and calls ring while Aegis is closed. Uses some battery. While Aegis is on screen it is connected either way.",
                state.online
            ) { CommsRepository.setOnline(it) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Push wake-ups", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.pushRegistered) "Registered with your UnifiedPush distributor. The relay pokes it when a message waits; the poke carries nothing."
                        else "Install a UnifiedPush distributor (ntfy from F-Droid works) and tap SET UP. No Google services are used.",
                        color = CMuted, fontSize = 11.sp, lineHeight = 15.sp
                    )
                }
                SmallButton(if (state.pushRegistered) "RENEW" else "SET UP", CAccent) {
                    context.findActivity()?.let { CommsRepository.registerPush(it) }
                }
            }
            if (!notificationsGranted) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Notifications are off, so new messages and incoming calls will not show until you open Aegis.", color = CCaution, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    SmallButton("ALLOW", CAccent, onClick = allowNotifications)
                }
            } else if (!callsChannelOk) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Incoming-call notifications are switched off or set to silent, so calls do not ring or pop up. Set \"Incoming calls\" to alert.",
                        color = CCaution, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f)
                    )
                    SmallButton("FIX", CAccent) { CommsNotifications.openCallsChannelSettings(context) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (microphoneGranted) "Microphone: allowed for calls."
                    else "Microphone: not allowed. Calls cannot be made or answered until it is.",
                    color = if (microphoneGranted) CMuted else CCaution, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f)
                )
                if (!microphoneGranted) SmallButton("ALLOW", CAccent) { askMicrophone.launch(Manifest.permission.RECORD_AUDIO) }
            }
            val batteryFree by rememberBatteryUnrestricted()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (batteryFree) "Battery: unrestricted. Android will not cut the background connection."
                    else "Battery: optimised. Android may cut the background connection, and then calls do not ring.",
                    color = if (batteryFree) CMuted else CCaution, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f)
                )
                if (!batteryFree) SmallButton("ALLOW", CAccent) { openBatterySettings(context) }
            }
            var fullScreen by remember { mutableStateOf(CommsNotifications.canUseFullScreen(context)) }
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) fullScreen = CommsNotifications.canUseFullScreen(context)
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            if (!fullScreen) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Full-screen calls: off. On a locked phone an incoming call shows only as a notification.",
                        color = CCaution, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f)
                    )
                    SmallButton("ALLOW", CAccent) { CommsNotifications.openFullScreenSettings(context) }
                }
            }
            Text(
                "Relay one-time keys: ${if (state.relayOneTimeKeys >= 0) state.relayOneTimeKeys else "unknown"} · topped up on sync",
                color = CMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }

        Card {
            Text("Identity", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text("Name shown to contacts", fontSize = 12.sp) }, singleLine = true,
                    modifier = Modifier.weight(1f), colors = fieldColors()
                )
                SmallButton("SAVE", CAccent, enabled = name.trim() != state.displayName) { scope.launch { CommsRepository.setDisplayName(name) } }
            }
            ToggleRow(
                "Listed number",
                if (state.listed) "Anyone on this relay who knows your number can look up your keys and message you."
                else "Unlisted: only people who scanned your code can reach you. Lookups by number fail.",
                state.listed, enabled = !state.busy
            ) { scope.launch { CommsRepository.setListed(it) } }
        }

        Card {
            Text("About Aegis numbers", color = CCaution, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(NOT_A_PHONE, color = CMuted, fontSize = 12.sp, lineHeight = 17.sp)
            Text(
                "Numbers are random and handed out by your relay at registration. Two people on different relays cannot message each other.",
                color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
            )
        }

        state.error?.let {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = CCritical, fontSize = 12.sp, modifier = Modifier.weight(1f))
                SmallButton("DISMISS", CMuted) { CommsRepository.clearError() }
            }
        }

        DiagnosticsCard()

        TextButton(onClick = { confirmUnpair = true }, contentPadding = PaddingValues(0.dp), enabled = !state.busy) {
            Text("DELETE IDENTITY AND NUMBER", color = CCritical, fontSize = 11.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── Background reliability and diagnostics ───────────────────────────────────

/**
 * Whether Android exempts Aegis from battery optimisation, re-read whenever the
 * screen resumes (the owner comes back from the system settings page).
 */
@Composable
private fun rememberBatteryUnrestricted(): State<Boolean> {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    val unrestricted = remember { mutableStateOf(power?.isIgnoringBatteryOptimizations(context.packageName) == true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unrestricted.value = power?.isIgnoringBatteryOptimizations(context.packageName) == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return unrestricted
}

/**
 * Asks Android to let Aegis run unrestricted. The direct request shows a
 * one-tap system dialog; where a phone does not offer it, the battery
 * optimisation list opens instead so the owner can find Aegis there.
 */
private fun openBatterySettings(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(direct) }.recoverCatching { context.startActivity(list) }
}

private val logTimeFmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)

/**
 * The comms event log: connections, envelopes that could not be read, calls
 * that arrived too late to ring. COPY puts it on the clipboard to send to
 * whoever is helping; it holds no message text.
 */
@Composable
private fun DiagnosticsCard() {
    val entries by CommsLog.entries.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Diagnostics", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            SmallButton("COPY", CAccent, enabled = entries.isNotEmpty()) {
                val text = entries.joinToString("\n") { "${logTimeFmt.format(Date(it.ts))}  ${it.text}" }
                clipboard.setText(AnnotatedString("Aegis comms log\n$text"))
            }
            SmallButton("CLEAR", CMuted, enabled = entries.isNotEmpty()) { CommsLog.clear() }
        }
        Text(
            "What happened with the relay, calls and envelopes on this phone. No message text is kept. " +
                "If a call or message goes missing, COPY this and send it along.",
            color = CMuted, fontSize = 11.sp, lineHeight = 15.sp
        )
        if (entries.isEmpty()) {
            Text("Nothing recorded yet.", color = CMuted, fontSize = 11.sp)
        } else {
            val shown = entries.asReversed().let { if (expanded) it else it.take(12) }
            for (e in shown) {
                Text(
                    "${logTimeFmt.format(Date(e.ts))}  ${e.text}",
                    color = CInkDim, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace
                )
            }
            if (entries.size > 12) {
                SmallButton(if (expanded) "SHOW LESS" else "SHOW ALL ${entries.size}", CMuted) { expanded = !expanded }
            }
        }
    }
}
