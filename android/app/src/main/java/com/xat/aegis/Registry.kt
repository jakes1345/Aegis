package com.xat.aegis

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/** Shared state between the scanning service, the NFC reader and the UI. */
object Registry {

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val _cell = MutableStateFlow(CellStatus())
    val cell: StateFlow<CellStatus> = _cell.asStateFlow()

    private val _timeline = MutableStateFlow<List<TimelineEvent>>(emptyList())
    val timeline: StateFlow<List<TimelineEvent>> = _timeline.asStateFlow()

    private val _map = MutableStateFlow(MapData())
    val map: StateFlow<MapData> = _map.asStateFlow()

    private val _nfc = MutableStateFlow<List<NfcTag>>(emptyList())
    val nfc: StateFlow<List<NfcTag>> = _nfc.asStateFlow()

    fun publish(list: List<Detection>) { _detections.value = list }

    /**
     * Read-modify-write of the scan status, retried if another thread got there
     * first. The BLE binder thread, the main thread and the service's coroutines all
     * call this; a plain `value = block(value)` let one of two racing updates vanish.
     */
    fun update(block: (ScanStatus) -> ScanStatus) { _status.update(block) }

    fun publishCell(status: CellStatus) { _cell.value = status }

    fun publishTimeline(events: List<TimelineEvent>) { _timeline.value = events }

    fun publishMap(data: MapData) { _map.value = data }

    fun addNfc(tag: NfcTag) {
        // Newest first, and a re-tap of the same card replaces the old row
        // rather than stacking duplicates.
        _nfc.update { current -> (listOf(tag) + current.filter { it.uid != tag.uid }).take(50) }
    }

    fun clearNfc() { _nfc.value = emptyList() }

    private val _wifi = MutableStateFlow<List<WifiAnomaly>>(emptyList())
    val wifi: StateFlow<List<WifiAnomaly>> = _wifi.asStateFlow()
    fun publishWifi(anomalies: List<WifiAnomaly>) { _wifi.value = anomalies }

    // --- Trusted devices -----------------------------------------------------
    //
    // Keyed on the Bluetooth address, which is what the scanner has in hand when it
    // decides whether to skip a device. It used to be keyed on the identity id
    // instead — a counter handed out fresh on every service start — so marking
    // something safe silenced nothing, and after a restart the saved id belonged to
    // whatever device happened to be seen seventh.
    //
    // Addresses on a rotating device are not stable by design, but the devices worth
    // trusting are the user's own headphones, watch and car, and those keep one.

    private const val TRUSTED_KEY = "trusted_addresses"

    private val _trusted = MutableStateFlow<Set<String>>(emptySet())
    val trusted: StateFlow<Set<String>> = _trusted.asStateFlow()

    /** Application context, held so trust survives the process being killed. */
    @Volatile
    private var appContext: Context? = null

    /** Call once per process, from both the activity and the service. */
    fun bindTrustStore(context: Context) {
        val app = context.applicationContext
        appContext = app
        _trusted.value = app
            .getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(TRUSTED_KEY, emptySet())
            ?.toSet() ?: emptySet()
    }

    fun trust(address: String) = setTrusted { it + address }

    fun untrust(address: String) = setTrusted { it - address }

    private fun setTrusted(change: (Set<String>) -> Set<String>) {
        val next = _trusted.updateAndGet(change)
        appContext
            ?.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            // A defensive copy: SharedPreferences must not be handed a set that is
            // later mutated, and callers keep a reference to the flow's value.
            ?.putStringSet(TRUSTED_KEY, HashSet(next))
            ?.apply()
    }

    private val _phoneHealth = MutableStateFlow(PhoneHealth())
    val phoneHealth: StateFlow<PhoneHealth> = _phoneHealth.asStateFlow()
    fun publishPhoneHealth(h: PhoneHealth) { _phoneHealth.value = h }
    fun updatePhoneHealth(block: (PhoneHealth) -> PhoneHealth) { _phoneHealth.update(block) }

    // --- Card vault ----------------------------------------------------------

    //
    // Decrypted cards exist here only between a successful unlock and the activity
    // going to the background; the rest of the time this holds Locked (or the
    // reason the vault could not be read), never the cards.

    private val _vault = MutableStateFlow<VaultState>(VaultState.Locked)
    val vault: StateFlow<VaultState> = _vault.asStateFlow()
    fun publishVault(state: VaultState) { _vault.value = state }
    fun lockVault() { _vault.value = VaultState.Locked }

    /**
     * The vault card currently being emulated via HCE, or null. Holds the label as
     * well as the id so the banner and STOP keep working after the vault re-locks —
     * emulation is typically used with Aegis in the background.
     */
    private val _emulating = MutableStateFlow<ArmedCard?>(null)
    val emulating: StateFlow<ArmedCard?> = _emulating.asStateFlow()
    fun setEmulating(card: ArmedCard?) { _emulating.value = card }

    /**
     * The result of the last BiometricPrompt, waiting to be acted on. The prompt's
     * callback publishes here instead of calling back into the Activity that showed
     * it: if that Activity has been recreated meanwhile, the new instance picks the
     * result up and finishes the operation the purpose describes.
     */
    private val _vaultAuth = MutableStateFlow<VaultAuthResult?>(null)
    val vaultAuth: StateFlow<VaultAuthResult?> = _vaultAuth.asStateFlow()
    fun publishVaultAuth(result: VaultAuthResult) { _vaultAuth.value = result }
    /** Takes the pending result, so it runs exactly once. */
    fun takeVaultAuth(): VaultAuthResult? = _vaultAuth.getAndUpdate { null }

    /** A save the vault refused because the UID is already stored; the UI asks. */
    private val _replacePrompt = MutableStateFlow<ReplacePrompt?>(null)
    val replacePrompt: StateFlow<ReplacePrompt?> = _replacePrompt.asStateFlow()
    fun setReplacePrompt(prompt: ReplacePrompt?) { _replacePrompt.value = prompt }

    /**
     * A tab the UI should switch to — set when a tag arrives through a system NFC
     * intent, which the user expects to land on the NFC tab. Consumed by the UI.
     */
    private val _tabRequest = MutableStateFlow<Int?>(null)
    val tabRequest: StateFlow<Int?> = _tabRequest.asStateFlow()
    fun requestTab(index: Int) { _tabRequest.value = index }
    fun takeTabRequest(): Int? = _tabRequest.getAndUpdate { null }

    fun reset() {
        _detections.value = emptyList()
        _status.value = ScanStatus()
        _cell.value = CellStatus()
        _map.value = MapData()
    }
}
