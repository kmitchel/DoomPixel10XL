package com.id.doom

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class PixelHapticsManager(private val context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    enum class HapticType(val id: Int) {
        TICK(1),       // UI / Pistol / Weapon Switch
        MEDIUM_THUD(2),// Shotgun / Rocket / Door Open
        HEAVY_IMPACT(3)// Player damage taken / BFG Explosion
    }

    fun triggerHaptic(typeId: Int) {
        if (vibrator == null || !vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (typeId) {
                HapticType.TICK.id -> {
                    val composition = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f)
                        .compose()
                    vibrator.vibrate(composition)
                }
                HapticType.MEDIUM_THUD.id -> {
                    val composition = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.4f, 50)
                        .compose()
                    vibrator.vibrate(composition)
                }
                HapticType.HEAVY_IMPACT.id -> {
                    val composition = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.8f, 100)
                        .compose()
                    vibrator.vibrate(composition)
                }
            }
        } else {
            // Fallback for older API levels
            val duration = when (typeId) {
                HapticType.TICK.id -> 20L
                HapticType.MEDIUM_THUD.id -> 60L
                else -> 120L
            }
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
