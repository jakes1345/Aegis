package com.trackdetect

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun update(block: (ScanStatus) -> ScanStatus) { _status.value = block(_status.value) }

    fun publishCell(status: CellStatus) { _cell.value = status }

    fun publishTimeline(events: List<TimelineEvent>) { _timeline.value = events }

    fun publishMap(data: MapData) { _map.value = data }

    fun addNfc(tag: NfcTag) {
        // Newest first, and a re-tap of the same card replaces the old row
        // rather than stacking duplicates.
        _nfc.value = (listOf(tag) + _nfc.value.filter { it.uid != tag.uid }).take(50)
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

    fun trust(address: String) = setTrusted(_trusted.value + address)

    fun untrust(address: String) = setTrusted(_trusted.value - address)

    private fun setTrusted(next: Set<String>) {
        _trusted.value = next
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

    fun reset() {
        _detections.value = emptyList()
        _status.value = ScanStatus()
        _cell.value = CellStatus()
        _map.value = MapData()
    }
}
