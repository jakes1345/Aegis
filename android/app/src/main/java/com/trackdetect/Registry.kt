package com.trackdetect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared state between the scanning service and the UI. */
object Registry {

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    fun publish(list: List<Detection>) {
        _detections.value = list
    }

    fun update(block: (ScanStatus) -> ScanStatus) {
        _status.value = block(_status.value)
    }

    fun reset() {
        _detections.value = emptyList()
        _status.value = ScanStatus()
    }
}
