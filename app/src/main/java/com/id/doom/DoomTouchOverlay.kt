package com.id.doom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class DoomTouchOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Per-pointer role tracking so multi-touch fingers never cancel each other
    private sealed class PointerRole {
        object Stick : PointerRole()
        object MenuDrag : PointerRole()
        data class MapPan(var lastX: Float, var lastY: Float) : PointerRole()
        data class Look(var lastX: Float, var lastY: Float) : PointerRole()
        data class FireAndLook(var lastX: Float, var lastY: Float) : PointerRole()
        data class FireAndStrafe(
            var lastX: Float,
            var lastY: Float,
            var isFiring: Boolean = true,
            var activeStrafeKey: Int = 0
        ) : PointerRole()
        data class HoldKey(val doomKey: Int) : PointerRole()
    }

    private val pointerRoles = mutableMapOf<Int, PointerRole>()
    private val density: Float get() = resources.displayMetrics.density

    // Paints
    private val paintBase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val paintStickBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(75, 20, 20, 25)
        style = Paint.Style.FILL
    }
    private val paintThumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 220, 40, 40)
        style = Paint.Style.FILL
    }
    private val paintThumbBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // Button Fill Paints
    private val paintBtnFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(165, 35, 35, 42)
        style = Paint.Style.FILL
    }
    private val paintBtnPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 230, 55, 55)
        style = Paint.Style.FILL
    }
    private val paintBtnFire = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(195, 185, 26, 26)
        style = Paint.Style.FILL
    }
    private val paintBtnUse = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 26, 38, 52)
        style = Paint.Style.FILL
    }
    private val paintBtnDisabled = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 90, 75, 30)
        style = Paint.Style.FILL
    }
    private val paintBtnEnabled = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 35, 160, 45)
        style = Paint.Style.FILL
    }
    private val paintBtnTurn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 160, 130, 15)
        style = Paint.Style.FILL
    }
    private val paintBtnStrafe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 28, 30, 36)
        style = Paint.Style.FILL
    }
    private val paintBtnPod = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 10, 10, 14)
        style = Paint.Style.FILL
    }
    private val paintBtnYes = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 30, 155, 40)
        style = Paint.Style.FILL
    }
    private val paintBtnNo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 185, 30, 30)
        style = Paint.Style.FILL
    }
    private val paintBtnPill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(195, 40, 70, 130)
        style = Paint.Style.FILL
    }

    // Button Stroke Outlines
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintStrokePod = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(35, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintStrokeUse = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 85, 175, 240)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintStrokeStrafe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 200, 205, 215)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintStrokeGold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 215, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintStrokeGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 80, 255, 80)
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val paintStrokeRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 80, 80)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // Industrial Tactical Monospace Typeface for HUD Labels
    private val hudTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    // Text Paints
    private val paintTextLarge = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = hudTypeface
        letterSpacing = 0.05f
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }
    private val paintTextMed = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = hudTypeface
        letterSpacing = 0.05f
        setShadowLayer(3.5f, 0f, 1.5f, Color.BLACK)
    }
    private val paintTextSmall = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = hudTypeface
        letterSpacing = 0.04f
        setShadowLayer(3f, 0f, 1.5f, Color.BLACK)
    }
    private val paintTextPill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.argb(245, 255, 240, 150)
        textAlign = Paint.Align.CENTER
        typeface = hudTypeface
        letterSpacing = 0.06f
        setShadowLayer(3.5f, 0f, 1.5f, Color.BLACK)
    }

    // Responsive Virtual Stick (Left side) with Dynamic Leash
    private var stickCenterBaseX = 0f
    private var stickCenterBaseY = 0f
    private var stickOriginX = 0f
    private var stickOriginY = 0f
    private var stickThumbX = 0f
    private var stickThumbY = 0f
    private var stickRadius = 140f
    private var stickActiveRadius = 100f

    // Action Buttons (Right-side vertically stacked tactical cluster):
    private val rectCluster = RectF()
    private val rectFireStrafeConsole = RectF()
    private val rectFire = RectF()
    private val rectFireHitZone = RectF()
    private val rectSideLeft = RectF()
    private val rectSideRight = RectF()
    private val rectUse = RectF()

    // Top Utility Buttons (Dynamically shown only when DOOM is listening for that button)
    private val rectEsc = RectF()
    private val rectEnter = RectF()
    private val rectBack = RectF()
    private val rectYes = RectF()
    private val rectNo = RectF()
    private val rectTab = RectF()

    // Automap Controls (Dynamically shown only when Automap is active)
    private val rectMapZoomIn = RectF()
    private val rectMapZoomOut = RectF()
    private val rectMapFollow = RectF()
    private val rectMapGrid = RectF()
    private val rectMapMark = RectF()
    private val rectMapClear = RectF()
    private val rectMapHud = RectF()

    // Menu Utility Buttons (Dynamically shown on top-right only when Menu is active)
    private val rectQuickSave = RectF()
    private val rectQuickLoad = RectF()
    private val rectGamma = RectF()
    private val rectUpscale = RectF()
    private val rectCheats = RectF()
    private val rectWad = RectF()
    private val rectUpdate = RectF()
    private var upscaleMode = 2 // 2 = textures + frame filter, 1 = textures only, 0 = off

    var isUpdateAvailable: Boolean = false
        set(value) {
            field = value
            postInvalidate()
        }

    private val paintBadgeRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 68, 68)
        style = Paint.Style.FILL
    }

    // Cheats Modal State & Bounds
    private var cheatsModalOpen = false
    private val rectCheatPanel = RectF()
    private val rectCheatClose = RectF()
    private val cheatRects = Array(12) { RectF() }
    private val cheatLabels = arrayOf(
        "IDDQD (GOD)",
        "IDKFA (ALL)",
        "IDFA (AMMO)",
        "IDCLIP (NOCLIP)",
        "IDDT (MAP)",
        "CHAINSAW",
        "INVULNERABLE",
        "BERSERK",
        "INVISIBLE",
        "LIGHT VISOR",
        "RAD SUIT",
        "NEXT LEVEL"
    )
    private val paintModalBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(238, 18, 18, 24)
        style = Paint.Style.FILL
    }

    // Prompt Buttons (Centered, visible when DOOM prompts Y/N)
    private val rectPromptYes = RectF()
    private val rectPromptNo = RectF()


    // Weapon buttons (Top bar — dynamically shown only during level gameplay for owned weapons)
    private val weaponRects = Array(7) { RectF() }

    private fun isKeyHeld(doomKey: Int): Boolean {
        for (role in pointerRoles.values) {
            if (role is PointerRole.HoldKey && role.doomKey == doomKey) return true
            if (role is PointerRole.FireAndLook && doomKey == DoomBridge.KEY_FIRE) return true
            if (role is PointerRole.FireAndStrafe) {
                if (role.isFiring && doomKey == DoomBridge.KEY_FIRE) return true
                if (role.activeStrafeKey == doomKey) return true
            }
        }
        return false
    }

    private fun updateDynamicTopBar(uiMask: Int) {
        val w = width
        if (w <= 0) return
        val density = resources.displayMetrics.density

        val topY = 10f * density
        val topH = 34f * density
        val btnGap = 6f * density

        // Clear all top button bounds first so hidden buttons are neither drawn nor hit-tested
        rectEsc.setEmpty()
        rectEnter.setEmpty()
        rectBack.setEmpty()
        rectYes.setEmpty()
        rectNo.setEmpty()
        rectTab.setEmpty()
        rectMapZoomIn.setEmpty()
        rectMapZoomOut.setEmpty()
        rectMapFollow.setEmpty()
        rectMapGrid.setEmpty()
        rectMapMark.setEmpty()
        rectMapClear.setEmpty()
        rectMapHud.setEmpty()
        rectQuickSave.setEmpty()
        rectQuickLoad.setEmpty()
        rectGamma.setEmpty()
        rectUpscale.setEmpty()
        rectCheats.setEmpty()
        rectWad.setEmpty()
        rectUpdate.setEmpty()
        for (i in 0 until 7) {
            weaponRects[i].setEmpty()
        }

        // Pack active top-left utility buttons neatly from left to right
        var curX = 12f * density

        if ((uiMask and DoomBridge.UI_BIT_MENU) != 0) {
            val escW = 54f * density
            rectEsc.set(curX, topY, curX + escW, topY + topH)
            curX = rectEsc.right + btnGap
        }

        if ((uiMask and DoomBridge.UI_BIT_ENTER) != 0) {
            val enterW = 58f * density
            rectEnter.set(curX, topY, curX + enterW, topY + topH)
            curX = rectEnter.right + btnGap
        }

        if ((uiMask and DoomBridge.UI_BIT_BACK) != 0) {
            val backW = 52f * density
            rectBack.set(curX, topY, curX + backW, topY + topH)
            curX = rectBack.right + btnGap
        }

        if ((uiMask and DoomBridge.UI_BIT_YN) != 0) {
            val ynW = 40f * density
            rectYes.set(curX, topY, curX + ynW, topY + topH)
            curX = rectYes.right + btnGap

            rectNo.set(curX, topY, curX + ynW, topY + topH)
            curX = rectNo.right + btnGap
        }

        if ((uiMask and DoomBridge.UI_BIT_MAP) != 0) {
            val tabW = 48f * density
            rectTab.set(curX, topY, curX + tabW, topY + topH)
            curX = rectTab.right + btnGap
        }

        // Automap buttons (only when Automap is open during level gameplay)
        if ((uiMask and DoomBridge.UI_BIT_AUTOMAP) != 0) {
            val smW = 36f * density
            val mdW = 44f * density

            rectMapZoomIn.set(curX, topY, curX + smW, topY + topH)
            curX = rectMapZoomIn.right + btnGap

            rectMapZoomOut.set(curX, topY, curX + smW, topY + topH)
            curX = rectMapZoomOut.right + btnGap

            rectMapFollow.set(curX, topY, curX + mdW, topY + topH)
            curX = rectMapFollow.right + btnGap

            rectMapGrid.set(curX, topY, curX + mdW, topY + topH)
            curX = rectMapGrid.right + btnGap

            rectMapMark.set(curX, topY, curX + mdW, topY + topH)
            curX = rectMapMark.right + btnGap

            rectMapClear.set(curX, topY, curX + mdW, topY + topH)

            val hudW = 280f * density
            val hudH = 22f * density
            val hudLeft = (w - hudW) / 2f
            val hudTop = topY + topH + 6f * density
            rectMapHud.set(hudLeft, hudTop, hudLeft + hudW, hudTop + hudH)
        }

        // Menu Utilities on top-right when DOOM Menu is active (and not in a Y/N prompt)
        if ((uiMask and DoomBridge.UI_BIT_MENU_UTILS) != 0) {
            val uW = 50f * density
            val uGap = 4f * density
            val totalMenuUtilsW = 7 * uW + 6 * uGap
            var ux = w - 10f * density - totalMenuUtilsW

            rectQuickSave.set(ux, topY, ux + uW, topY + topH)
            ux += uW + uGap

            rectQuickLoad.set(ux, topY, ux + uW, topY + topH)
            ux += uW + uGap

            rectGamma.set(ux, topY, ux + uW, topY + topH)
            ux += uW + uGap

            rectUpscale.set(ux, topY, ux + uW, topY + topH)
            ux += uW + uGap

            rectCheats.set(ux, topY, ux + uW, topY + topH)
            ux += uW + uGap

            rectWad.set(ux, topY, ux + uW, topY + topH)
            ux += uW + uGap

            rectUpdate.set(ux, topY, ux + uW, topY + topH)
        } else {
            // Pack active weapon slot buttons on the top-right (only during level gameplay & owned)
            val activeSlots = (0 until 7).filter { DoomBridge.isWeaponSlotActive(uiMask, it) }
            if (activeSlots.isNotEmpty()) {
                val wpnW = 32f * density
                val wpnSpacing = 5f * density
                val totalW = activeSlots.size * wpnW + (activeSlots.size - 1) * wpnSpacing
                var wpnX = w - 12f * density - totalW
                for (slotIndex in activeSlots) {
                    weaponRects[slotIndex].set(wpnX, topY, wpnX + wpnW, topY + topH)
                    wpnX += wpnW + wpnSpacing
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density

        // Configure font sizes scaled to display density
        paintTextLarge.textSize = 18.5f * density
        paintTextMed.textSize = 13f * density
        paintTextSmall.textSize = 11.5f * density
        paintTextPill.textSize = 11f * density

        paintBase.strokeWidth = 3f * density
        paintThumbBorder.strokeWidth = 2.5f * density
        paintStroke.strokeWidth = 2f * density
        paintStrokePod.strokeWidth = 1.5f * density
        paintStrokeUse.strokeWidth = 2f * density
        paintStrokeStrafe.strokeWidth = 2f * density
        paintStrokeGold.strokeWidth = 2.5f * density
        paintStrokeGreen.strokeWidth = 2.5f * density
        paintStrokeRed.strokeWidth = 2.5f * density

        // Text drop shadow for high contrast over any game background
        paintTextLarge.setShadowLayer(3f * density, 0f, 1.5f * density, Color.argb(180, 0, 0, 0))
        paintTextMed.setShadowLayer(2.5f * density, 0f, 1.2f * density, Color.argb(180, 0, 0, 0))
        paintTextSmall.setShadowLayer(2f * density, 0f, 1f * density, Color.argb(180, 0, 0, 0))

        // 4:3 DOOM Viewport and Letterbox Areas
        val viewportW = (h * 4f / 3f).coerceAtMost(w.toFloat())
        val viewportLeft = (w - viewportW) / 2f
        val viewportRight = viewportLeft + viewportW

        stickRadius = 66f * density
        stickActiveRadius = 58f * density

        // 1. Virtual stick base: centered horizontally in the left letterbox area,
        // positioned comfortably below the landscape front camera punch-hole (which sits at h / 2)
        if (viewportLeft > stickRadius * 1.5f) {
            stickCenterBaseX = viewportLeft / 2f
        } else {
            stickCenterBaseX = (100f * density).coerceAtMost(w * 0.25f)
        }
        stickCenterBaseY = h - (116f * density)
        stickOriginX = stickCenterBaseX
        stickOriginY = stickCenterBaseY
        stickThumbX = stickCenterBaseX
        stickThumbY = stickCenterBaseY

        // 2. Action Buttons: centered horizontally in the right letterbox area with generous padding
        // Outer pod: rectCluster
        // Tier 1 (Top): rectUse (same height as FIRE)
        // Tier 2 (Main): Integrated FIRE & STRAFE Console (center horizontally aligned with left joystick stickCenterBaseY)
        //   - Left Wing: rectSideLeft (◀ STRAFE LEFT)
        //   - Center Core: rectFire (FIRE) - substantially enlarged for instant, reliable weapon firing
        //   - Right Wing: rectSideRight (STRAFE RIGHT ▶)
        //   - Combined Bounds: rectFireStrafeConsole
        //   - Expanded Capture Zone: rectFireHitZone (guarantees 100% reliable tap-to-fire on the very first touch)
        val rightLetterboxW = w - viewportRight
        val clusterW = if (rightLetterboxW > 170f * density) {
            156f * density
        } else if (rightLetterboxW > 140f * density) {
            (rightLetterboxW - (16f * density)).coerceAtMost(156f * density)
        } else {
            132f * density
        }

        val colLeft: Float
        val colRight: Float
        if (rightLetterboxW > clusterW) {
            val rightLetterboxCenterX = viewportRight + (rightLetterboxW / 2f)
            colLeft = rightLetterboxCenterX - (clusterW / 2f)
            colRight = rightLetterboxCenterX + (clusterW / 2f)
        } else {
            colRight = w - (16f * density)
            colLeft = colRight - clusterW
        }

        val btnH = 62f * density
        val rowGap = 10f * density
        val podPad = 10f * density
        val wingW = 30f * density
        val dockGap = 3.5f * density

        // Tier 2 (Main): Integrated FIRE & STRAFE console (horizontally aligned with left joystick stickCenterBaseY)
        val fireCenterY = stickCenterBaseY
        val fireTop = fireCenterY - (btnH / 2f)
        val fireBottom = fireCenterY + (btnH / 2f)

        // Left Strafe Wing (◀)
        rectSideLeft.set(
            colLeft,
            fireTop,
            colLeft + wingW,
            fireBottom
        )

        // Center FIRE Core (~89dp wide x 62dp high, 2.3x previous area)
        rectFire.set(
            colLeft + wingW + dockGap,
            fireTop,
            colRight - wingW - dockGap,
            fireBottom
        )

        // Right Strafe Wing (▶)
        rectSideRight.set(
            colRight - wingW,
            fireTop,
            colRight,
            fireBottom
        )

        // Combined console bounds for touch hit-testing and sliding
        rectFireStrafeConsole.set(
            colLeft,
            fireTop,
            colRight,
            fireBottom
        )

        // Tier 1 (Top): USE button (same height as FIRE)
        val useBottom = fireTop - rowGap
        val useTop = useBottom - btnH
        rectUse.set(
            colLeft,
            useTop,
            colRight,
            useBottom
        )

        // Pod Backdrop surrounding the vertical stack with 10dp tactical padding on all sides
        rectCluster.set(
            colLeft - podPad,
            useTop - podPad,
            colRight + podPad,
            fireBottom + podPad
        )

        // Generous Hit Zone for FIRE & STRAFE: captures any touch from useBottom downwards,
        // extending outward into the pod padding so the weapon reliably fires on the very first touch
        rectFireHitZone.set(
            colLeft - (podPad * 1.5f),
            useBottom,
            (colRight + (podPad * 1.5f)).coerceAtMost(w.toFloat()),
            (fireBottom + (podPad * 2f)).coerceAtMost(h.toFloat())
        )

        // 3. Center Prompt Buttons (For Y/N questions)
        val promptBtnW = 125f * density
        val promptBtnH = 52f * density
        val promptY = h * 0.64f
        rectPromptYes.set(
            w / 2f - promptBtnW - (12f * density),
            promptY,
            w / 2f - (12f * density),
            promptY + promptBtnH
        )
        rectPromptNo.set(
            w / 2f + (12f * density),
            promptY,
            w / 2f + (12f * density) + promptBtnW,
            promptY + promptBtnH
        )

        // 4. Cheats Modal Layout (Centered 3x4 grid)
        val panelW = (440f * density).coerceAtMost(w * 0.88f)
        val panelH = (275f * density).coerceAtMost(h * 0.82f)
        rectCheatPanel.set(
            (w - panelW) / 2f,
            (h - panelH) / 2f,
            (w + panelW) / 2f,
            (h + panelH) / 2f
        )
        val closeW = 72f * density
        val closeH = 30f * density
        rectCheatClose.set(
            rectCheatPanel.right - closeW - 12f * density,
            rectCheatPanel.top + 10f * density,
            rectCheatPanel.right - 12f * density,
            rectCheatPanel.top + 10f * density + closeH
        )
        val gridLeft = rectCheatPanel.left + 14f * density
        val gridTop = rectCheatPanel.top + 48f * density
        val gridRight = rectCheatPanel.right - 14f * density
        val gridBottom = rectCheatPanel.bottom - 14f * density
        val cols = 3
        val rows = 4
        val cellGap = 8f * density
        val cellW = (gridRight - gridLeft - (cols - 1) * cellGap) / cols
        val cellH = (gridBottom - gridTop - (rows - 1) * cellGap) / rows
        for (i in 0 until 12) {
            val r = i / cols
            val c = i % cols
            val cx = gridLeft + c * (cellW + cellGap)
            val cy = gridTop + r * (cellH + cellGap)
            cheatRects[i].set(cx, cy, cx + cellW, cy + cellH)
        }

        updateDynamicTopBar(DoomBridge.nativeGetUiStateMask())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val uiMask = DoomBridge.nativeGetUiStateMask()
        updateDynamicTopBar(uiMask)

        // 1. Draw Left Virtual Stick (Clean ring + thumb, no clutter labels or mode toggle)
        canvas.drawCircle(stickCenterBaseX, stickCenterBaseY, stickRadius, paintStickBg)
        canvas.drawCircle(stickCenterBaseX, stickCenterBaseY, stickRadius, paintBase)
        canvas.drawCircle(stickThumbX, stickThumbY, stickRadius * 0.38f, paintThumb)
        canvas.drawCircle(stickThumbX, stickThumbY, stickRadius * 0.38f, paintThumbBorder)

        // 3. Top Utility Buttons (Only drawn when DOOM is listening for them)
        if (!rectEsc.isEmpty) {
            drawButton(canvas, rectEsc, if (isKeyHeld(DoomBridge.KEY_ESCAPE)) paintBtnPressed else paintBtnFill, paintStroke, "MENU", paintTextMed)
        }
        if (!rectEnter.isEmpty) {
            drawButton(canvas, rectEnter, if (isKeyHeld(DoomBridge.KEY_ENTER)) paintBtnPressed else paintBtnFill, paintStroke, "ENTER", paintTextMed)
        }
        if (!rectBack.isEmpty) {
            drawButton(canvas, rectBack, if (isKeyHeld(DoomBridge.KEY_BACKSPACE)) paintBtnPressed else paintBtnFill, paintStroke, "BACK", paintTextMed)
        }
        if (!rectYes.isEmpty) {
            drawButton(canvas, rectYes, if (isKeyHeld(DoomBridge.KEY_Y)) paintBtnPressed else paintBtnYes, paintStrokeGreen, "Y", paintTextMed)
        }
        if (!rectNo.isEmpty) {
            drawButton(canvas, rectNo, if (isKeyHeld(DoomBridge.KEY_N)) paintBtnPressed else paintBtnNo, paintStrokeRed, "N", paintTextMed)
        }
        if (!rectTab.isEmpty) {
            drawButton(canvas, rectTab, if (isKeyHeld(DoomBridge.KEY_TAB)) paintBtnPressed else paintBtnFill, paintStroke, "MAP", paintTextMed)
        }

        // Automap Buttons (Only drawn when Automap is open)
        if (!rectMapZoomIn.isEmpty) {
            drawButton(canvas, rectMapZoomIn, if (isKeyHeld(DoomBridge.KEY_EQUALS)) paintBtnPressed else paintBtnFill, paintStrokeGold, "Z+", paintTextSmall)
            drawButton(canvas, rectMapZoomOut, if (isKeyHeld(DoomBridge.KEY_MINUS)) paintBtnPressed else paintBtnFill, paintStrokeGold, "Z-", paintTextSmall)
            val isFreePan = (uiMask and DoomBridge.UI_BIT_FREE_PAN) != 0
            val flwFill = if (isFreePan) paintBtnTurn else paintBtnEnabled
            val flwStroke = if (isFreePan) paintStrokeGold else paintStrokeGreen
            val flwLabel = if (isFreePan) "PAN" else "FLW"
            drawButton(canvas, rectMapFollow, if (isKeyHeld(DoomBridge.KEY_F)) paintBtnPressed else flwFill, flwStroke, flwLabel, paintTextSmall)
            drawButton(canvas, rectMapGrid, if (isKeyHeld(DoomBridge.KEY_G)) paintBtnPressed else paintBtnFill, paintStroke, "GRID", paintTextSmall)
            drawButton(canvas, rectMapMark, if (isKeyHeld(DoomBridge.KEY_M)) paintBtnPressed else paintBtnFill, paintStroke, "MRK", paintTextSmall)
            drawButton(canvas, rectMapClear, if (isKeyHeld(DoomBridge.KEY_C)) paintBtnPressed else paintBtnFill, paintStroke, "CLR", paintTextSmall)
        }

        // Tactical Automap Navigation & Compass HUD Pill
        if (!rectMapHud.isEmpty) {
            val heading = DoomBridge.nativeGetPlayerAngle()
            val headingInt = (heading + 0.5f).toInt() % 360
            val cardinal = when (headingInt) {
                in 338..359, in 0..22 -> "N"
                in 23..67 -> "NE"
                in 68..112 -> "E"
                in 113..157 -> "SE"
                in 158..202 -> "S"
                in 203..247 -> "SW"
                in 248..292 -> "W"
                in 293..337 -> "NW"
                else -> "N"
            }
            val isFreePan = (uiMask and DoomBridge.UI_BIT_FREE_PAN) != 0
            val modeStr = if (isFreePan) "FREE PAN (DRAG TO PAN)" else "FOLLOWING PLAYER"
            val hudText = "HEADING: %03d° %s  |  %s".format(headingInt, cardinal, modeStr)

            canvas.drawRoundRect(rectMapHud, 6f * density, 6f * density, paintBtnPod)
            canvas.drawRoundRect(rectMapHud, 6f * density, 6f * density, if (isFreePan) paintStrokeGold else paintStrokeGreen)
            canvas.drawText(hudText, rectMapHud.centerX(), rectMapHud.centerY() + (4f * density), paintTextSmall)
        }

        // Menu Utilities (Only drawn on top-right when DOOM Menu is active)
        if (!rectQuickSave.isEmpty) {
            drawButton(canvas, rectQuickSave, if (isKeyHeld(DoomBridge.KEY_F6)) paintBtnPressed else paintBtnUse, paintStroke, "QSAVE", paintTextSmall)
            drawButton(canvas, rectQuickLoad, if (isKeyHeld(DoomBridge.KEY_F9)) paintBtnPressed else paintBtnUse, paintStroke, "QLOAD", paintTextSmall)
            drawButton(canvas, rectGamma, if (isKeyHeld(DoomBridge.KEY_F11)) paintBtnPressed else paintBtnFill, paintStrokeGold, "GAMMA", paintTextSmall)
            val hdLabel = when (upscaleMode) {
                2 -> "HD: FULL"
                1 -> "HD: TEX"
                else -> "HD: OFF"
            }
            val hdFill = if (upscaleMode > 0) paintBtnEnabled else paintBtnDisabled
            val hdStroke = if (upscaleMode > 0) paintStrokeGreen else paintStroke
            drawButton(canvas, rectUpscale, hdFill, hdStroke, hdLabel, paintTextSmall)
            drawButton(canvas, rectCheats, if (cheatsModalOpen) paintBtnPressed else paintBtnStrafe, paintStrokeGold, "CHEATS", paintTextSmall)
            drawButton(canvas, rectWad, paintBtnTurn, paintStrokeGold, "WAD", paintTextSmall)
            val updFill = if (isUpdateAvailable) paintBtnEnabled else paintBtnTurn
            val updStroke = if (isUpdateAvailable) paintStrokeGreen else paintStrokeGold
            drawButton(canvas, rectUpdate, updFill, updStroke, "OTA", paintTextSmall)
            if (isUpdateAvailable) {
                canvas.drawCircle(rectUpdate.right - 5f * density, rectUpdate.top + 5f * density, 3.5f * density, paintBadgeRed)
            }
        }

        // Weapons Bar (Only drawn during active gameplay for owned weapons)
        for (i in 0 until 7) {
            if (!weaponRects[i].isEmpty) {
                val wpnKey = '1'.code + i
                drawButton(
                    canvas,
                    weaponRects[i],
                    if (isKeyHeld(wpnKey)) paintBtnPressed else paintBtnFill,
                    paintStroke,
                    "${i + 1}",
                    paintTextMed
                )
            }
        }

        // 4. Action Buttons (Right-side vertically stacked tactical cluster)
        // Background Tactical Pod
        canvas.drawRoundRect(rectCluster, 20f * density, 20f * density, paintBtnPod)
        canvas.drawRoundRect(rectCluster, 20f * density, 20f * density, paintStrokePod)

        // Tier 1 (Top): USE button (same height as FIRE)
        drawButton(
            canvas,
            rectUse,
            if (isKeyHeld(DoomBridge.KEY_USE)) paintBtnPressed else paintBtnUse,
            paintStrokeUse,
            "USE",
            paintTextLarge,
            cornerRadius = 14f * density
        )

        // Tier 2 (Main): Integrated FIRE & STRAFE Console (center horizontally aligned with left joystick)
        val fireHeld = isKeyHeld(DoomBridge.KEY_FIRE)
        val strafeLHeld = isKeyHeld(DoomBridge.KEY_STRAFE_L)
        val strafeRHeld = isKeyHeld(DoomBridge.KEY_STRAFE_R)

        // Left Strafe Wing (◀)
        drawButton(
            canvas,
            rectSideLeft,
            if (strafeLHeld) paintBtnPressed else paintBtnStrafe,
            if (strafeLHeld) paintStrokeGold else paintStrokeStrafe,
            "◀",
            paintTextLarge,
            cornerRadius = 12f * density
        )

        // Center FIRE Core
        drawButton(
            canvas,
            rectFire,
            if (fireHeld) paintBtnPressed else paintBtnFire,
            if (fireHeld) paintStrokeGold else paintStrokeRed,
            "FIRE",
            paintTextLarge,
            cornerRadius = 12f * density
        )

        // Right Strafe Wing (▶)
        drawButton(
            canvas,
            rectSideRight,
            if (strafeRHeld) paintBtnPressed else paintBtnStrafe,
            if (strafeRHeld) paintStrokeGold else paintStrokeStrafe,
            "▶",
            paintTextLarge,
            cornerRadius = 12f * density
        )

        // 5. Prompt Overlay (When DOOM prompts Y/N)
        if ((uiMask and DoomBridge.UI_BIT_YN) != 0) {
            drawButton(
                canvas,
                rectPromptYes,
                if (isKeyHeld(DoomBridge.KEY_Y)) paintBtnPressed else paintBtnYes,
                paintStrokeGreen,
                "✔ YES (Y)",
                paintTextLarge
            )
            drawButton(
                canvas,
                rectPromptNo,
                if (isKeyHeld(DoomBridge.KEY_N)) paintBtnPressed else paintBtnNo,
                paintStrokeRed,
                "✖ NO (N)",
                paintTextLarge
            )
        }

        // 6. Cheats Modal Overlay
        if (cheatsModalOpen) {
            canvas.drawRoundRect(rectCheatPanel, 14f * density, 14f * density, paintModalBg)
            canvas.drawRoundRect(rectCheatPanel, 14f * density, 14f * density, paintStrokeGold)
            canvas.drawText(
                "DOOM CHEATS & LEVEL WARP",
                rectCheatPanel.left + 16f * density + 115f * density,
                rectCheatPanel.top + 29f * density,
                paintTextMed
            )
            drawButton(canvas, rectCheatClose, paintBtnNo, paintStrokeRed, "CLOSE", paintTextSmall)
            for (i in 0 until 12) {
                drawButton(canvas, cheatRects[i], paintBtnFill, paintStrokeGold, cheatLabels[i], paintTextSmall)
            }
        }

        // Periodically refresh overlay so top bar buttons appear/disappear on state changes without high-rate wakeups
        postInvalidateDelayed(100)
    }

    private fun drawButton(
        canvas: Canvas,
        rect: RectF,
        fillPaint: Paint,
        strokePaint: Paint,
        label: String,
        textPaint: Paint,
        cornerRadius: Float = 10f * resources.displayMetrics.density
    ) {
        val density = resources.displayMetrics.density
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
        canvas.drawText(label, rect.centerX(), rect.centerY() + (5f * density), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                handlePointerDown(pointerId, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val px = event.getX(i)
                    val py = event.getY(i)
                    handlePointerMove(pid, px, py)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(pointerId)
            }
            MotionEvent.ACTION_CANCEL -> {
                resetAllInputs()
            }
        }
        invalidate()
        return true
    }

    private fun pressAndHoldKey(pid: Int, doomKey: Int) {
        pointerRoles[pid] = PointerRole.HoldKey(doomKey)
        DoomBridge.onHapticEvent(1)
        DoomBridge.nativeSendKey(1, doomKey)
    }

    private fun handlePointerDown(pid: Int, x: Float, y: Float) {
        val uiMask = DoomBridge.nativeGetUiStateMask()
        updateDynamicTopBar(uiMask)

        // 0. Cheats Modal Overlay (Intercepts touches when open)
        if (cheatsModalOpen) {
            if (rectCheatClose.contains(x, y) || !rectCheatPanel.contains(x, y)) {
                cheatsModalOpen = false
                DoomBridge.onHapticEvent(1)
                return
            }
            for (i in 0 until 12) {
                if (cheatRects[i].contains(x, y)) {
                    DoomBridge.onHapticEvent(2)
                    DoomBridge.nativeTriggerCheat(i)
                    cheatsModalOpen = false
                    return
                }
            }
            return
        }

        // 2. Center Prompt Buttons (When Y/N prompt active)
        if ((uiMask and DoomBridge.UI_BIT_YN) != 0) {
            if (rectPromptYes.contains(x, y)) {
                pressAndHoldKey(pid, DoomBridge.KEY_Y)
                return
            }
            if (rectPromptNo.contains(x, y)) {
                pressAndHoldKey(pid, DoomBridge.KEY_N)
                return
            }
        }

        // 3. Right-Side Action Buttons: Integrated FIRE & STRAFE Console + USE
        // Prioritize USE for touches in rectUse or upper tactical pod area above rectUse.bottom
        if (rectUse.contains(x, y) || (y < rectUse.bottom && rectCluster.contains(x, y))) {
            // Pure KEY_USE (opens doors/switches/respawns, never triggers menu or level reload!)
            pressAndHoldKey(pid, DoomBridge.KEY_USE)
            return
        }

        // Taps within the FIRE & STRAFE console or its expanded hit zone guarantee firing on the 1st try!
        if (rectFireStrafeConsole.contains(x, y) || rectFireHitZone.contains(x, y)) {
            val role = PointerRole.FireAndStrafe(x, y, isFiring = true, activeStrafeKey = 0)
            pointerRoles[pid] = role
            DoomBridge.onHapticEvent(1)
            DoomBridge.nativeSendKey(1, DoomBridge.KEY_FIRE)

            // Direct touch on left wing (◀) or right wing (▶) activates strafe alongside fire
            if (rectSideLeft.contains(x, y) || x <= rectFire.left) {
                role.activeStrafeKey = DoomBridge.KEY_STRAFE_L
                DoomBridge.nativeSendKey(1, DoomBridge.KEY_STRAFE_L)
            } else if (rectSideRight.contains(x, y) || x >= rectFire.right) {
                role.activeStrafeKey = DoomBridge.KEY_STRAFE_R
                DoomBridge.nativeSendKey(1, DoomBridge.KEY_STRAFE_R)
            }
            // Center core between rectFire.left and rectFire.right is 100% pure weapon fire
            postInvalidate()
            return
        }

        // 4. Top Utility Buttons (RectF is empty when hidden, so contains(x, y) is only true when visible & listening)
        if (rectEsc.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_ESCAPE)
            return
        }
        if (rectEnter.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_ENTER)
            return
        }
        if (rectBack.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_BACKSPACE)
            return
        }
        if (rectYes.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_Y)
            return
        }
        if (rectNo.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_N)
            return
        }
        if (rectTab.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_TAB)
            return
        }

        // Automap Controls (Only hit-tested when Automap is open)
        if (rectMapZoomIn.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_EQUALS)
            return
        }
        if (rectMapZoomOut.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_MINUS)
            return
        }
        if (rectMapFollow.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_F)
            return
        }
        if (rectMapGrid.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_G)
            return
        }
        if (rectMapMark.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_M)
            return
        }
        if (rectMapClear.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_C)
            return
        }

        // Menu Utility Buttons (Only hit-tested when Menu is active)
        if (rectQuickSave.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_F6)
            return
        }
        if (rectQuickLoad.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_F9)
            return
        }
        if (rectGamma.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_F11)
            return
        }
        if (rectUpscale.contains(x, y)) {
            upscaleMode = (upscaleMode + 2) % 3 // Cycles 2 (FULL) -> 1 (TEX) -> 0 (OFF) -> 2 (FULL)
            DoomBridge.onHapticEvent(1)
            DoomBridge.nativeSetUpscaleMode(upscaleMode)
            return
        }
        if (rectCheats.contains(x, y)) {
            cheatsModalOpen = !cheatsModalOpen
            DoomBridge.onHapticEvent(1)
            return
        }
        if (rectWad.contains(x, y)) {
            DoomBridge.onHapticEvent(1)
            DoomBridge.requestWadPicker()
            return
        }
        if (rectUpdate.contains(x, y)) {
            DoomBridge.onHapticEvent(1)
            DoomBridge.requestUpdateCheck()
            return
        }

        // 5. Weapons Bar (RectF is empty unless in active level gameplay and weapon is owned)
        for (i in 0 until 7) {
            if (weaponRects[i].contains(x, y)) {
                pressAndHoldKey(pid, '1'.code + i)
                return
            }
        }

        // Compute centered 4:3 viewport bounds matching DoomSurfaceView
        val viewportW = (height * 4f / 3f).coerceAtMost(width.toFloat())
        val viewportH = (viewportW * 3f / 4f).coerceAtMost(height.toFloat())
        val viewportLeft = (width - viewportW) / 2f
        val viewportTop = (height - viewportH) / 2f

        // 6. Menu Direct Touch + Slider Dragging (Checked BEFORE Left Virtual Stick so Episode/Skill/Slider items inside the 4:3 viewport are never swallowed!)
        val distToStick = hypot((x - stickCenterBaseX).toDouble(), (y - stickCenterBaseY).toDouble()).toFloat()
        if (DoomBridge.nativeIsMenuActive() && distToStick > stickRadius * 0.85f &&
            x in viewportLeft..(viewportLeft + viewportW) && y in viewportTop..(viewportTop + viewportH)) {
            // Only one finger owns direct menu interaction at a time.
            if (pointerRoles.values.any { it is PointerRole.MenuDrag }) return
            pointerRoles[pid] = PointerRole.MenuDrag
            DoomBridge.onHapticEvent(1)
            val normX = ((x - viewportLeft) / viewportW).coerceIn(0f, 1f)
            val normY = ((y - viewportTop) / viewportH).coerceIn(0f, 1f)
            DoomBridge.nativeTouchScreen(normX, normY)
            return
        }

        // 7. Left Virtual Stick (Touch anchor + progressive walking curve + anti-veer corridor)
        val hasStickPointer = pointerRoles.values.any { it is PointerRole.Stick }
        if (!hasStickPointer && (distToStick <= stickRadius * 1.45f || (x < viewportLeft && y > height * 0.40f))) {
            pointerRoles[pid] = PointerRole.Stick
            // Anchor origin to touch point so initial touch never causes accidental lurch or run!
            stickOriginX = x
            stickOriginY = y
            stickThumbX = x
            stickThumbY = y
            updateStickPosition(x, y)
            return
        }

        // 8. Automap Free Drag Panning: when automap is open, dragging on the map surface pans the view
        if ((uiMask and DoomBridge.UI_BIT_AUTOMAP) != 0 &&
            (x in viewportLeft..(viewportLeft + viewportW) || x > viewportLeft + viewportW) &&
            !rectCluster.contains(x, y) && !rectFireHitZone.contains(x, y) && y > (50f * density)) {
            pointerRoles[pid] = PointerRole.MapPan(x, y)
            return
        }

        // 9. Title Screen / Intermission Direct Touch (Never during active level gameplay)
        val inLevelGameplay = (uiMask and DoomBridge.UI_BIT_MAP) != 0
        if (!inLevelGameplay && x in viewportLeft..(viewportLeft + viewportW) && y in viewportTop..(viewportTop + viewportH)) {
            val normX = ((x - viewportLeft) / viewportW).coerceIn(0f, 1f)
            val normY = ((y - viewportTop) / viewportH).coerceIn(0f, 1f)
            DoomBridge.nativeTouchScreen(normX, normY)
            return
        }

        // 10. Right-Screen Swipe-to-Look
        pointerRoles[pid] = PointerRole.Look(x, y)
    }

    private fun calcAnalogForward(rawY: Float): Float {
        val deadzone = 0.07f
        val absY = kotlin.math.abs(rawY)
        if (absY <= deadzone) return 0f
        val sign = if (rawY >= 0f) 1f else -1f
        val t = ((absY - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)

        // Generous walking band: 65% of physical stick throw is dedicated to walking (speeds 0 to 25/50 in DOOM)
        // Outer 35% ramps smoothly into running (speeds 25 to 50/50 in DOOM)
        val walkZone = 0.65f
        val speed = if (t <= walkZone) {
            val progress = t / walkZone
            0.50f * (0.25f * progress * progress + 0.75f * progress)
        } else {
            val progress = (t - walkZone) / (1f - walkZone)
            0.50f + 0.50f * (progress * progress * 0.35f + progress * 0.65f)
        }
        return (sign * speed).coerceIn(-1f, 1f)
    }

    private fun calcAnalogTurn(rawX: Float, rawY: Float): Float {
        val absX = kotlin.math.abs(rawX)
        val absY = kotlin.math.abs(rawY)

        // Forward-motion anti-veer: when walking forward or backward, provide an axial deadband
        // so slight natural thumb wobble doesn't spin the camera
        var deadzone = 0.08f
        if (absY > 0.18f) {
            deadzone = deadzone.coerceAtLeast((absY * 0.35f).coerceAtMost(0.25f))
        }

        if (absX <= deadzone) return 0f
        val sign = if (rawX >= 0f) 1f else -1f
        val t = ((absX - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
        val turnVal = t * (0.4f + 0.6f * t)
        return (sign * turnVal).coerceIn(-1f, 1f)
    }

    private fun updateStickPosition(x: Float, y: Float) {
        var dx = x - stickOriginX
        var dy = y - stickOriginY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        // Dynamic Leash: if thumb drags past stickActiveRadius, pull stickOrigin behind thumb
        // so reversing thumb direction responds instantaneously without lag
        if (dist > stickActiveRadius) {
            val angle = atan2(dy.toDouble(), dx.toDouble())
            stickOriginX = x - (stickActiveRadius * cos(angle)).toFloat()
            stickOriginY = y - (stickActiveRadius * sin(angle)).toFloat()

            // Clamp origin drift within 50% radius of visual base center
            val maxDrift = stickRadius * 0.50f
            val baseDx = stickOriginX - stickCenterBaseX
            val baseDy = stickOriginY - stickCenterBaseY
            val baseDist = hypot(baseDx.toDouble(), baseDy.toDouble()).toFloat()
            if (baseDist > maxDrift) {
                val baseAngle = atan2(baseDy.toDouble(), baseDx.toDouble())
                stickOriginX = stickCenterBaseX + (maxDrift * cos(baseAngle)).toFloat()
                stickOriginY = stickCenterBaseY + (maxDrift * sin(baseAngle)).toFloat()
            }

            dx = x - stickOriginX
            dy = y - stickOriginY
        }

        // Visual thumb position clamped to visual ring
        val visualDx = x - stickCenterBaseX
        val visualDy = y - stickCenterBaseY
        val visualDist = hypot(visualDx.toDouble(), visualDy.toDouble()).toFloat()
        if (visualDist > stickRadius) {
            val vAngle = atan2(visualDy.toDouble(), visualDx.toDouble())
            stickThumbX = stickCenterBaseX + (stickRadius * cos(vAngle)).toFloat()
            stickThumbY = stickCenterBaseY + (stickRadius * sin(vAngle)).toFloat()
        } else {
            stickThumbX = x
            stickThumbY = y
        }

        val rawX = (dx / stickActiveRadius).coerceIn(-1f, 1f)
        val rawY = (-dy / stickActiveRadius).coerceIn(-1f, 1f)

        val normY = calcAnalogForward(rawY)
        val normX = calcAnalogTurn(rawX, rawY)

        // Forward/backward moves along the progressive walk/run curve; X turns with anti-veer stabilization
        DoomBridge.nativeSendAnalog(forward = normY, strafe = 0f, turn = normX)
    }

    private fun handlePointerMove(pid: Int, x: Float, y: Float) {
        when (val role = pointerRoles[pid]) {
            is PointerRole.Stick -> {
                updateStickPosition(x, y)
            }
            is PointerRole.MenuDrag -> {
                if (DoomBridge.nativeIsMenuActive()) {
                    val viewportW = (height * 4f / 3f).coerceAtMost(width.toFloat())
                    val viewportLeft = (width - viewportW) / 2f
                    val normX = ((x - viewportLeft) / viewportW).coerceIn(0f, 1f)
                    // Movement may adjust a slider captured on press, never select again.
                    DoomBridge.nativeDragMenu(normX)
                }
            }
            is PointerRole.MapPan -> {
                val deltaX = x - role.lastX
                val deltaY = y - role.lastY
                role.lastX = x
                role.lastY = y
                if (deltaX != 0f || deltaY != 0f) {
                    val viewportW = (height * 4f / 3f).coerceAtMost(width.toFloat())
                    val scale = 320f / viewportW.coerceAtLeast(1f)
                    DoomBridge.nativePanAutomap(deltaX * scale, deltaY * scale)
                }
            }
            is PointerRole.Look -> {
                val deltaX = x - role.lastX
                val deltaY = y - role.lastY
                role.lastX = x
                role.lastY = y
                if (deltaX != 0f || deltaY != 0f) {
                    DoomBridge.nativeSendLook(deltaX, deltaY)
                }
            }
            is PointerRole.FireAndLook -> {
                val deltaX = x - role.lastX
                val deltaY = y - role.lastY
                role.lastX = x
                role.lastY = y
                if (deltaX != 0f || deltaY != 0f) {
                    DoomBridge.nativeSendLook(deltaX, deltaY)
                }
            }
            is PointerRole.FireAndStrafe -> {
                // Dynamic strafe steering across the integrated console:
                // Sliding/leaning left triggers STRAFE_L, sliding/leaning right triggers STRAFE_R, center zone fires without strafe
                val newStrafeKey = when {
                    x <= rectFire.left || rectSideLeft.contains(x, y) -> DoomBridge.KEY_STRAFE_L
                    x >= rectFire.right || rectSideRight.contains(x, y) -> DoomBridge.KEY_STRAFE_R
                    else -> 0
                }

                if (newStrafeKey != role.activeStrafeKey) {
                    if (role.activeStrafeKey != 0) {
                        DoomBridge.nativeSendKey(0, role.activeStrafeKey)
                    }
                    role.activeStrafeKey = newStrafeKey
                    if (newStrafeKey != 0) {
                        DoomBridge.nativeSendKey(1, newStrafeKey)
                        DoomBridge.onHapticEvent(1)
                    }
                    postInvalidate()
                }
            }
            else -> {}
        }
    }

    private fun handlePointerUp(pid: Int) {
        when (val role = pointerRoles.remove(pid)) {
            is PointerRole.Stick -> {
                stickOriginX = stickCenterBaseX
                stickOriginY = stickCenterBaseY
                stickThumbX = stickCenterBaseX
                stickThumbY = stickCenterBaseY
                DoomBridge.nativeSendAnalog(forward = 0f, strafe = 0f, turn = 0f)
            }
            is PointerRole.FireAndLook -> {
                if (!isKeyHeld(DoomBridge.KEY_FIRE)) {
                    DoomBridge.nativeSendKey(0, DoomBridge.KEY_FIRE)
                }
            }
            is PointerRole.FireAndStrafe -> {
                if (role.activeStrafeKey != 0) {
                    DoomBridge.nativeSendKey(0, role.activeStrafeKey)
                }
                if (!isKeyHeld(DoomBridge.KEY_FIRE)) {
                    DoomBridge.nativeSendKey(0, DoomBridge.KEY_FIRE)
                }
                postInvalidate()
            }
            is PointerRole.HoldKey -> {
                if (!isKeyHeld(role.doomKey)) {
                    DoomBridge.nativeSendKey(0, role.doomKey)
                }
            }
            else -> {}
        }
    }

    private fun resetAllInputs() {
        for (role in pointerRoles.values) {
            when (role) {
                is PointerRole.FireAndLook -> DoomBridge.nativeSendKey(0, DoomBridge.KEY_FIRE)
                is PointerRole.FireAndStrafe -> {
                    if (role.activeStrafeKey != 0) DoomBridge.nativeSendKey(0, role.activeStrafeKey)
                    DoomBridge.nativeSendKey(0, DoomBridge.KEY_FIRE)
                }
                is PointerRole.HoldKey -> DoomBridge.nativeSendKey(0, role.doomKey)
                else -> {}
            }
        }
        pointerRoles.clear()
        stickOriginX = stickCenterBaseX
        stickOriginY = stickCenterBaseY
        stickThumbX = stickCenterBaseX
        stickThumbY = stickCenterBaseY
        DoomBridge.nativeSendAnalog(0f, 0f, 0f)
    }
}
