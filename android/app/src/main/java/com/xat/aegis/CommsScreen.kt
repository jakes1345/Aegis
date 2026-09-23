package com.xat.aegis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xat.aegis.comms.CommsNotifications
import com.xat.aegis.comms.CommsRepository
import com.xat.aegis.comms.Direction
import com.xat.aegis.comms.SmsMessage
import com.xat.aegis.comms.Thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/**
 * The COMMS tab: the owner's real phone number, its conversations, and the
 * pairing screen that connects the app to the relay.
 *
 * [openPeer] is a thread to jump straight into (from a notification), consumed
 * once.
 */
@Composable
fun CommsScreen(openPeer: String?, onPeerConsumed: () -> Unit) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    var peer by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(openPeer) {
        if (openPeer != null) {
            peer = openPeer
            onPeerConsumed()
        }
    }

    val current = peer
    when {
        !state.paired -> PairingScreen()
        current != null -> ThreadScreen(peer = current, onBack = { peer = null })
        else -> ThreadListScreen(onOpen = { peer = it })
    }
}

// ── Pairing ──────────────────────────────────────────────────────────────────

@Composable
private fun PairingScreen() {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Text("COMMS", color = CInk, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text("A real phone number for calls and texts, relayed through your own server", color = CMuted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))

        Column(Modifier.fillMaxWidth().background(CPanel, CShape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pair with your relay", color = CInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Deploy comms-worker from the Aegis repository to Cloudflare, then enter its URL and the " +
                    "enrollment secret you set. The secret is used once; this phone receives its own token.",
                color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Worker URL", fontSize = 12.sp) },
                placeholder = { Text("https://aegis-comms.you.workers.dev", fontSize = 12.sp, color = CMuted) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = fieldColors()
            )
            OutlinedTextField(
                value = secret, onValueChange = { secret = it },
                label = { Text("Enrollment secret", fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = fieldColors()
            )
            state.error?.let { Text(it, color = CCritical, fontSize = 12.sp) }
            Button(
                onClick = {
                    scope.launch { CommsRepository.pair(url, secret, android.os.Build.MODEL ?: "Aegis") }
                },
                enabled = !state.busy && url.isNotBlank() && secret.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CAccent, contentColor = Color(0xFF12161D)),
                shape = CShape, modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.busy) "PAIRING…" else "PAIR", fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
        }

        Column(Modifier.fillMaxWidth().background(CPanel, CShape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What is protected", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Traffic between this phone and your relay, and between the relay and the carrier, is " +
                    "encrypted in transit. Texts and calls to ordinary phones travel the phone network as " +
                    "normal SMS and voice, readable by the carriers. Push notifications carry no message " +
                    "content. Messages are cached on this phone under a hardware-backed key.",
                color = CMuted, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
    }
}

// ── Thread list ──────────────────────────────────────────────────────────────

@Composable
private fun ThreadListScreen(onOpen: (String) -> Unit) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val version by CommsRepository.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val threads by produceState(initialValue = emptyList<Thread>(), version) {
        value = withContext(Dispatchers.IO) { CommsRepository.threads() }
    }
    var newMessage by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val now = rememberTick()

    if (newMessage) {
        NewMessageDialog(onDismiss = { newMessage = false }, onOpen = { newMessage = false; onOpen(it) })
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            containerColor = CPanel,
            title = { Text("Unpair from the relay?", color = CInk, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This phone forgets the relay and deletes its cached messages. The relay keeps the " +
                        "conversation; pairing again re-downloads it.",
                    color = CInkDim, fontSize = 13.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmUnpair = false; scope.launch { CommsRepository.unpair() } }) {
                    Text("UNPAIR", color = CCritical, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text("CANCEL", color = CMuted, letterSpacing = 1.sp) }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("COMMS", color = CInk, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(state.number ?: "Number not reported by the relay", color = CAccent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
            TextButton(onClick = { scope.launch { CommsRepository.sync() } }, enabled = !state.busy) {
                Text(if (state.busy) "SYNCING" else "SYNC", color = CMuted, fontSize = 11.sp, letterSpacing = 1.sp)
            }
            TextButton(onClick = { newMessage = true }) {
                Text("+ NEW", color = CClear, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        val statusLine = buildString {
            append(if (state.pushRegistered) "Push on" else "Push off — texts arrive on sync")
            if (state.lastSync > 0L) append(" · synced ${agoText(now, state.lastSync)}")
        }
        Text(statusLine, color = if (state.pushRegistered) CMuted else CCaution, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        state.error?.let { Text(it, color = CCritical, fontSize = 12.sp) }
        Spacer(Modifier.height(10.dp))

        if (threads.isEmpty()) {
            Column(Modifier.fillMaxWidth().background(CPanel, CShape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("No conversations yet", color = CInkDim, fontSize = 14.sp)
                Text("Texts to your number appear here. Tap + NEW to start one.", color = CMuted, fontSize = 12.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                items(threads, key = { it.peer }) { t -> ThreadRow(t, onClick = { onOpen(t.peer) }) }
                item(key = "§footer") { Spacer(Modifier.height(8.dp)) }
            }
        }
        TextButton(onClick = { confirmUnpair = true }, contentPadding = PaddingValues(0.dp)) {
            Text("UNPAIR", color = CMuted, fontSize = 10.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ThreadRow(t: Thread, onClick: () -> Unit) {
    val m = t.lastMessage
    val stripe = when {
        t.unread > 0 -> CAccent
        m.failed -> CCritical
        else -> CRule
    }
    Row(Modifier.fillMaxWidth().clip(CShape).background(CPanel).clickable(onClick = onClick).height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(stripe))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.peer, color = CInk, fontSize = 14.sp, fontWeight = if (t.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
                )
                if (t.unread > 0) {
                    Text(
                        t.unread.toString(),
                        color = Color(0xFF12161D), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(CAccent, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(dateTimeFmt.format(Date(m.ts)), color = CMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Text(
                (if (m.direction == Direction.OUT) "You: " else "") + previewText(m),
                color = if (t.unread > 0) CInkDim else CMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (m.failed) Text("Not delivered", color = CCritical, fontSize = 11.sp)
        }
    }
}

// ── Thread ───────────────────────────────────────────────────────────────────

@Composable
private fun ThreadScreen(peer: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val version by CommsRepository.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val messages by produceState(initialValue = emptyList<SmsMessage>(), version, peer) {
        value = withContext(Dispatchers.IO) { CommsRepository.messages(peer) }
    }
    var draft by rememberSaveable(peer) { mutableStateOf("") }
    var sendError by remember(peer) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Opening the thread reads it; the notification for it goes away too.
    LaunchedEffect(peer, version) {
        withContext(Dispatchers.IO) { CommsRepository.markRead(peer) }
        CommsNotifications.cancel(context, peer)
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("‹ BACK", color = CAccent, fontSize = 13.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(peer, color = CInk, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        }
        Text("SMS via ${state.number ?: "your number"} · carrier-visible, not end-to-end encrypted", color = CMuted, fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))

        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages, key = { it.id }) { m -> MessageBubble(m, onRetryStatus = { scope.launch { CommsRepository.refresh(m.id) } }) }
            item(key = "§footer") { Spacer(Modifier.height(4.dp)) }
        }

        sendError?.let { Text(it, color = CCritical, fontSize = 12.sp) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it.take(1600) },
                placeholder = { Text("Message", color = CMuted, fontSize = 13.sp) },
                modifier = Modifier.weight(1f), maxLines = 5, colors = fieldColors()
            )
            Button(
                onClick = {
                    val text = draft.trim()
                    if (text.isEmpty()) return@Button
                    sendError = null
                    scope.launch {
                        CommsRepository.send(peer, text)
                            .onSuccess { draft = "" }
                            .onFailure { sendError = it.message ?: "Send failed" }
                    }
                },
                enabled = !state.busy && draft.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CAccent, contentColor = Color(0xFF12161D)),
                shape = CShape
            ) { Text("SEND", fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp) }
        }
    }
}

@Composable
private fun MessageBubble(m: SmsMessage, onRetryStatus: () -> Unit) {
    val mine = m.direction == Direction.OUT
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .fillMaxWidth(0.82f)
                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = if (mine) 10.dp else 2.dp, bottomEnd = if (mine) 2.dp else 10.dp))
                .background(if (mine) CBlue.copy(alpha = 0.22f) else CPanelHi)
                .border(1.dp, if (m.failed) CCritical.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (m.body.isNotBlank()) Text(m.body, color = CInk, fontSize = 14.sp, lineHeight = 19.sp)
            m.media.forEach { item ->
                Text("Attachment · ${item.contentType}", color = CInkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(timeFmt.format(Date(m.ts)), color = CMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                if (mine) {
                    val (label, colour) = when (m.status) {
                        "delivered" -> "delivered" to CClear
                        "sent" -> "sent" to CMuted
                        "queued", "accepted", "sending" -> "sending" to CMuted
                        "failed", "undelivered" -> "failed" to CCritical
                        else -> m.status to CMuted
                    }
                    Text(label, color = colour, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable(onClick = onRetryStatus))
                }
            }
            if (m.failed && m.error != null) Text(m.error, color = CCritical, fontSize = 11.sp)
        }
    }
}

// ── New message ──────────────────────────────────────────────────────────────

@Composable
private fun NewMessageDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var number by remember { mutableStateOf("") }
    val normalised = normalisePhone(number)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CPanel,
        title = { Text("New message", color = CInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = number, onValueChange = { number = it },
                    label = { Text("Phone number", fontSize = 12.sp) },
                    placeholder = { Text("+1 555 123 4567", color = CMuted, fontSize = 12.sp) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth()
                )
                Text(
                    normalised?.let { "Will send to $it" } ?: "Enter a number with its country code, or a 10-digit North American number",
                    color = if (normalised != null) CClear else CMuted, fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { normalised?.let(onOpen) }, enabled = normalised != null) {
                Text("OPEN", color = CAccent, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = CMuted, letterSpacing = 1.sp) } }
    )
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Mirrors the relay's normalisation so the thread key matches what it stores. */
internal fun normalisePhone(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val plus = trimmed.startsWith("+")
    val digits = trimmed.filter { it.isDigit() }
    if (digits.length < 7 || digits.length > 15) return null
    return when {
        plus -> "+$digits"
        digits.length == 10 -> "+1$digits"
        digits.length == 11 && digits.startsWith("1") -> "+$digits"
        else -> "+$digits"
    }
}

private fun previewText(m: SmsMessage): String = when {
    m.body.isNotBlank() -> m.body
    m.media.isNotEmpty() -> "Picture message"
    else -> "Message"
}

private fun agoText(now: Long, ts: Long): String {
    val s = ((now - ts) / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

@Composable
private fun rememberTick(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = CAccent, unfocusedBorderColor = CRule,
    focusedTextColor = CInk, unfocusedTextColor = CInk,
    cursorColor = CAccent, focusedLabelColor = CAccent, unfocusedLabelColor = CMuted
)
