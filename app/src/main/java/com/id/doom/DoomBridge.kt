package com.id.doom

import android.view.Surface

object DoomBridge {

    init {
        System.loadLibrary("doom")
    }

    // Doom Keycodes (matching doomkeys.h)
    const val KEY_RIGHTARROW = 0xae
    const val KEY_LEFTARROW = 0xac
    const val KEY_UPARROW = 0xad
    const val KEY_DOWNARROW = 0xaf
    const val KEY_STRAFE_L = 0xa0
    const val KEY_STRAFE_R = 0xa1
    const val KEY_USE = 0xa2
    const val KEY_FIRE = 0xa3
    const val KEY_ESCAPE = 27
    const val KEY_ENTER = 13
    const val KEY_TAB = 9
    const val KEY_BACKSPACE = 0x7f
    const val KEY_RSHIFT = 0x80 + 0x36 // Run
    const val KEY_Y = 'y'.code
    const val KEY_N = 'n'.code

    // Function & Automap Keys
    const val KEY_F6 = 0x80 + 0x40     // QuickSave
    const val KEY_F9 = 0x80 + 0x43     // QuickLoad
    const val KEY_F11 = 0x80 + 0x57    // Gamma Brightness
    const val KEY_EQUALS = '='.code    // Automap Zoom In
    const val KEY_MINUS = '-'.code     // Automap Zoom Out
    const val KEY_F = 'f'.code         // Automap Follow Toggle
    const val KEY_G = 'g'.code         // Automap Grid Toggle
    const val KEY_M = 'm'.code         // Automap Add Mark
    const val KEY_C = 'c'.code         // Automap Clear Marks

    // UI State Bitmask flags returned by nativeGetUiStateMask()
    const val UI_BIT_MENU = 1 shl 0
    const val UI_BIT_ENTER = 1 shl 1
    const val UI_BIT_BACK = 1 shl 2
    const val UI_BIT_YN = 1 shl 3
    const val UI_BIT_MAP = 1 shl 4
    const val UI_BIT_AUTOMAP = 1 shl 5
    const val UI_BIT_MENU_UTILS = 1 shl 6

    fun isWeaponSlotActive(mask: Int, slotIndex: Int): Boolean {
        return (mask and (1 shl (8 + slotIndex))) != 0
    }

    // Listeners for Haptics, Quit, and WAD selection
    var hapticListener: ((Int) -> Unit)? = null
    var quitListener: (() -> Unit)? = null
    var wadPickerListener: (() -> Unit)? = null
    @Volatile
    var firstFrameListener: ((Int) -> Unit)? = null

    @JvmStatic
    fun onHapticEvent(effectType: Int) {
        hapticListener?.invoke(effectType)
    }

    @JvmStatic
    fun onQuitEvent() {
        quitListener?.invoke()
    }

    @JvmStatic
    fun onFirstFrameEvent(sessionId: Int) {
        firstFrameListener?.invoke(sessionId)
    }

    fun requestWadPicker() {
        wadPickerListener?.invoke()
    }

    // Native JNI functions
    @JvmStatic external fun nativePrepareInit(): Int
    @JvmStatic external fun nativeInit(wadPath: String, saveDir: String): Int
    @JvmStatic external fun nativeSetSurface(surface: Surface)
    @JvmStatic external fun nativeSurfaceDestroyed()
    @JvmStatic external fun nativeSendKey(pressed: Int, doomKey: Int)
    @JvmStatic external fun nativeSendAnalog(forward: Float, strafe: Float, turn: Float)
    @JvmStatic external fun nativeSendLook(deltaX: Float, deltaY: Float)
    @JvmStatic external fun nativeSetAutoRun(enabled: Boolean)
    @JvmStatic external fun nativeTriggerCheat(cheatId: Int)
    @JvmStatic external fun nativeSetUpscaleMode(mode: Int)
    @JvmStatic external fun nativeGetUpscaleMode(): Int
    @JvmStatic external fun nativeIsMenuActive(): Boolean
    @JvmStatic external fun nativeIsPromptActive(): Boolean
    @JvmStatic external fun nativeGetUiStateMask(): Int
    @JvmStatic external fun nativeTouchScreen(normX: Float, normY: Float)
    @JvmStatic external fun nativeSetPaused(paused: Boolean)
    @JvmStatic external fun nativeStop()
}
