package com.selman.klavye.input

import com.selman.klavye.layout.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Test

class ShiftControllerTest {

    private fun controller() = ShiftController()

    @Test
    fun baslangicOff() {
        val c = controller()
        assertEquals(ShiftState.OFF, c.state)
    }

    @Test
    fun offOnCapsLockOffDongusu() {
        val c = controller()
        c.onShiftKey()
        assertEquals(ShiftState.ON, c.state)
        c.onShiftKey()
        assertEquals(ShiftState.CAPS_LOCK, c.state)
        c.onShiftKey()
        assertEquals(ShiftState.OFF, c.state)
    }

    @Test
    fun otomatikOnShiftCapsLockYapar() {
        val c = controller()
        c.applyAutoCaps(true)
        assertEquals(ShiftState.ON, c.state)
        c.onShiftKey()
        assertEquals(ShiftState.CAPS_LOCK, c.state)
    }

    @Test
    fun elleOnShiftCapsLockYapar() {
        val c = controller()
        c.onShiftKey()
        assertEquals(ShiftState.ON, c.state)
        c.onShiftKey()
        assertEquals(ShiftState.CAPS_LOCK, c.state)
    }

    @Test
    fun onArdindanKarakterOff() {
        val c = controller()
        c.onShiftKey()
        assertEquals(ShiftState.ON, c.state)
        c.onCharacterTyped()
        assertEquals(ShiftState.OFF, c.state)
    }

    @Test
    fun capsLockKarakterdeKalir() {
        val c = controller()
        c.onShiftKey()
        c.onShiftKey()
        assertEquals(ShiftState.CAPS_LOCK, c.state)
        c.onCharacterTyped()
        assertEquals(ShiftState.CAPS_LOCK, c.state)
    }

    @Test
    fun otoCapsTrueOnFalseOff() {
        val c = controller()
        c.applyAutoCaps(true)
        assertEquals(ShiftState.ON, c.state)
        c.applyAutoCaps(false)
        assertEquals(ShiftState.OFF, c.state)
    }

    @Test
    fun elleOnOtoCapsFalseOnKalir() {
        val c = controller()
        c.onShiftKey()
        c.applyAutoCaps(false)
        assertEquals(ShiftState.ON, c.state)
    }

    @Test
    fun capsLockOtoCapsDegistirmez() {
        val c = controller()
        c.onShiftKey()
        c.onShiftKey()
        c.applyAutoCaps(false)
        assertEquals(ShiftState.CAPS_LOCK, c.state)
        c.applyAutoCaps(true)
        assertEquals(ShiftState.CAPS_LOCK, c.state)
    }

    @Test
    fun otomatikOnKarakterOff() {
        val c = controller()
        c.applyAutoCaps(true)
        c.onCharacterTyped()
        assertEquals(ShiftState.OFF, c.state)
    }

    @Test
    fun capsLocktanOffSonrasiTekrarOn() {
        val c = controller()
        c.onShiftKey()
        c.onShiftKey()
        c.onShiftKey()
        assertEquals(ShiftState.OFF, c.state)
        c.onShiftKey()
        assertEquals(ShiftState.ON, c.state)
    }

    @Test
    fun resetHerDurumdanOff() {
        val c = controller()
        c.reset()
        assertEquals(ShiftState.OFF, c.state)

        c.onShiftKey()
        c.reset()
        assertEquals(ShiftState.OFF, c.state)

        c.applyAutoCaps(true)
        c.reset()
        assertEquals(ShiftState.OFF, c.state)

        c.onShiftKey()
        c.onShiftKey()
        assertEquals(ShiftState.CAPS_LOCK, c.state)
        c.reset()
        assertEquals(ShiftState.OFF, c.state)
    }
}
