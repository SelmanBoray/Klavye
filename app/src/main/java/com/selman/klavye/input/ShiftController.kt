package com.selman.klavye.input

import com.selman.klavye.layout.ShiftState

/**
 * Shift durum makinesi (saf Kotlin, android.* yok).
 *
 * Shift tuşu zamandan bağımsız üç durumlu bir döngüdür: OFF -> ON -> CAPS_LOCK -> OFF
 * (ON otomatik auto-caps ile de kurulmuş olabilir; döngü yine ON -> CAPS_LOCK ilerler).
 */
class ShiftController {

    var state: ShiftState = ShiftState.OFF
        private set

    /** ON durumu otomatik (auto-caps) mi yoksa elle mi kuruldu. Yalnız state == ON iken anlamlı. */
    private var autoOn: Boolean = false

    fun onShiftKey() {
        when (state) {
            ShiftState.OFF -> {
                state = ShiftState.ON
                autoOn = false
            }
            ShiftState.ON -> {
                // Otomatik ya da elle fark etmez: ON'dan sonraki basış CAPS_LOCK.
                state = ShiftState.CAPS_LOCK
                autoOn = false
            }
            ShiftState.CAPS_LOCK -> {
                state = ShiftState.OFF
                autoOn = false
            }
        }
    }

    fun onCharacterTyped() {
        if (state == ShiftState.ON) {
            state = ShiftState.OFF
            autoOn = false
        }
    }

    fun applyAutoCaps(shouldCapitalize: Boolean) {
        if (shouldCapitalize) {
            if (state == ShiftState.OFF) {
                state = ShiftState.ON
                autoOn = true
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
    }
}
