package com.rrrainielll.smsbridge.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * In-process change notification. Receivers and workers bump it after changing settings or the
 * message queue so a visible UI can refresh. It carries no data.
 */
object AppEvents {
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick

    fun bump() = _tick.update { it + 1 }
}
