package com.trackdetect

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

    private val _trusted = MutableStateFlow<Set<String>>(emptySet())
    val trusted: StateFlow<Set<String>> = _trusted.asStateFlow()
    fun trust(key: String) { _trusted.value = _trusted.value + key }
    fun untrust(key: String) { _trusted.value = _trusted.value - key }

    fun reset() {
        _detections.value = emptyList()
        _status.value = ScanStatus()
        _cell.value = CellStatus()
        _map.value = MapData()
    }
}
