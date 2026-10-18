package com.selman.klavye.input

import com.selman.klavye.layout.ShiftState

/**
 * Shift durum makinesi (saf Kotlin, android.* yok).
 *
 * @param now milisaniye cinsinden monoton saat (serviste SystemClock::uptimeMillis).
 */
class ShiftController(private val now: () -> Long) {

    var state: ShiftState = ShiftState.OFF
        private set

    /** ON durumu otomatik (auto-caps) mi yoksa elle mi kuruldu. Yalnız state == ON iken anlamlı. */
    private var autoOn: Boolean = false

    /** Durumu elle ON yapan shift basışının zamanı. */
    private var manualOnAt: Long = 0L
    private var hasManualOnAt: Boolean = false

    fun onShiftKey() {
        when (state) {
            ShiftState.OFF -> {
                state = ShiftState.ON
                autoOn = false
                manualOnAt = now()
                hasManualOnAt = true
            }
            ShiftState.ON -> {
                val t = now()
                val isDoubleTap = !autoOn && hasManualOnAt && (t - manualOnAt) <= DOUBLE_TAP_MS
                state = if (isDoubleTap) ShiftState.CAPS_LOCK else ShiftState.OFF
                autoOn = false
                hasManualOnAt = false
            }
            ShiftState.CAPS_LOCK -> {
                state = ShiftState.OFF
                autoOn = false
                hasManualOnAt = false
            }
        }
    }

    fun onCharacterTyped() {
        if (state == ShiftState.ON) {
            state = ShiftState.OFF
            autoOn = false
            hasManualOnAt = false
        }
    }

    fun applyAutoCaps(shouldCapitalize: Boolean) {
        if (shouldCapitalize) {
            if (state == ShiftState.OFF) {
                state = ShiftState.ON
                autoOn = true
                hasManualOnAt = false
            }
        } else {
            if (state == ShiftState.ON && autoOn) {
                state = ShiftState.OFF
                autoOn = false
            }
        }
    }

    fun reset() {
        state = ShiftState.OFF
        autoOn = false
        manualOnAt = 0L
        hasManualOnAt = false
    }

    private companion object {
        const val DOUBLE_TAP_MS = 300L
    }
}
