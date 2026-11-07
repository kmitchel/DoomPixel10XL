package com.id.doom

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class PixelHapticsManager(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    enum class HapticType(val id: Int, val fallbackDurationMs: Long) {
        TICK(1, 20L),
        MEDIUM_THUD(2, 60L),
        HEAVY_IMPACT(3, 120L)
    }

    // API level alone does not guarantee support for composition primitives.
    private val effects: Map<Int, VibrationEffect> = HapticType.entries.associate { type ->
        val fallback = VibrationEffect.createOneShot(type.fallbackDurationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null) {
            val primitives = when (type) {
                HapticType.TICK -> intArrayOf(VibrationEffect.Composition.PRIMITIVE_CLICK)
                HapticType.MEDIUM_THUD -> intArrayOf(
                    VibrationEffect.Composition.PRIMITIVE_THUD,
                    VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
                )
                HapticType.HEAVY_IMPACT -> intArrayOf(
                    VibrationEffect.Composition.PRIMITIVE_THUD,
                    VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
                )
            }
            if (vibrator.arePrimitivesSupported(*primitives).all { it }) {
                val composition = VibrationEffect.startComposition()
                when (type) {
                    HapticType.TICK -> composition.addPrimitive(primitives[0], 0.6f)
                    HapticType.MEDIUM_THUD -> composition
                        .addPrimitive(primitives[0], 1.0f)
                        .addPrimitive(primitives[1], 0.4f, 50)
                    HapticType.HEAVY_IMPACT -> composition
                        .addPrimitive(primitives[0], 1.0f)
                        .addPrimitive(primitives[1], 0.8f, 100)
                }
                composition.compose()
            } else fallback
        } else fallback
        type.id to effect
    }

    fun triggerHaptic(typeId: Int) {
        val device = vibrator ?: return
        val effect = effects[typeId] ?: return
        if (device.hasVibrator()) device.vibrate(effect)
    }
}
