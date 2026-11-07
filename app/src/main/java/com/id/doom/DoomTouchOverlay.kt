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
        data class Look(var lastX: Float, var lastY: Float) : PointerRole()
        data class FireAndLook(var lastX: Float, var lastY: Float) : PointerRole()
        data class HoldKey(val doomKey: Int) : PointerRole()
    }

    private val pointerRoles = mutableMapOf<Int, PointerRole>()

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
        color = Color.argb(185, 185, 25, 25)
        style = Paint.Style.FILL
    }
    private val paintBtnUse = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 20, 105, 185)
        style = Paint.Style.FILL
    }
    private val paintBtnRunOff = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 90, 75, 30)
        style = Paint.Style.FILL
    }
    private val paintBtnRunOn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 35, 160, 45)
        style = Paint.Style.FILL
    }
    private val paintBtnTurn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 160, 130, 15)
        style = Paint.Style.FILL
    }
    private val paintBtnStrafe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 100, 40, 160)
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
        strokeWidth = 3.5f
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
    private val paintTextVersion = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.argb(210, 225, 225, 225)
        textAlign = Paint.Align.CENTER
        typeface = hudTypeface
        letterSpacing = 0.04f
        setShadowLayer(2.5f, 0f, 1f, Color.BLACK)
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

    // Auto-run toggle state (ON by default so full stick deflection runs and partial deflection walks)
    private var autoRunEnabled = true

    // Action Buttons (Right side):
    // FIRE flanked by dedicated Strafe Left (◀ STRF) and Strafe Right (STRF ▶)
    private val rectFire = RectF()
    private val rectSideLeft = RectF()
    private val rectSideRight = RectF()
    private val rectUse = RectF()
    private val rectRun = RectF()

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

    // Menu Utility Buttons (Dynamically shown on top-right only when Menu is active)
    private val rectQuickSave = RectF()
    private val rectQuickLoad = RectF()
    private val rectGamma = RectF()
    private val rectUpscale = RectF()
    private val rectCheats = RectF()
    private val rectWad = RectF()
    private var upscaleMode = 2 // 2 = HD: AI+ (4x WAD + 1280x800 xBR+CAS), 1 = HD: TEX (4x WAD), 0 = HD: OFF

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

    // Top Version Indicator
    private val rectVersion = RectF()

    // Weapon buttons (Top bar — dynamically shown only during level gameplay for owned weapons)
    private val weaponRects = Array(7) { RectF() }

    private fun isKeyHeld(doomKey: Int): Boolean {
        for (role in pointerRoles.values) {
            if (role is PointerRole.HoldKey && role.doomKey == doomKey) return true
            if (role is PointerRole.FireAndLook && doomKey == DoomBridge.KEY_FIRE) return true
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
        rectQuickSave.setEmpty()
        rectQuickLoad.setEmpty()
        rectGamma.setEmpty()
        rectUpscale.setEmpty()
        rectCheats.setEmpty()
        rectWad.setEmpty()
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
        }

        // Menu Utilities on top-right when DOOM Menu is active (and not in a Y/N prompt)
        if ((uiMask and DoomBridge.UI_BIT_MENU_UTILS) != 0) {
            val uW = 55f * density
            val uGap = 5f * density
            val totalMenuUtilsW = 6 * uW + 5 * uGap
            var ux = w - 12f * density - totalMenuUtilsW

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
        paintTextLarge.textSize = 17f * density
        paintTextMed.textSize = 13f * density
        paintTextSmall.textSize = 11.5f * density
        paintTextPill.textSize = 11f * density
        paintTextVersion.textSize = 9.5f * density

        paintBase.strokeWidth = 3f * density
        paintThumbBorder.strokeWidth = 2.5f * density
        paintStroke.strokeWidth = 2f * density
        paintStrokeGold.strokeWidth = 2.5f * density
        paintStrokeGreen.strokeWidth = 2.5f * density
        paintStrokeRed.strokeWidth = 2.5f * density

        stickRadius = 72f * density
        stickActiveRadius = 50f * density

        // 1. Virtual stick base at bottom-left
        stickCenterBaseX = 118f * density
        stickCenterBaseY = h - (112f * density)
        stickOriginX = stickCenterBaseX
        stickOriginY = stickCenterBaseY
        stickThumbX = stickCenterBaseX
        stickThumbY = stickCenterBaseY

        // Version Watermark (Bottom Center so it doesn't crowd top bar)
        rectVersion.set(
            w / 2f - (78f * density),
            h - (22f * density),
            w / 2f + (78f * density),
            h - (4f * density)
        )

        // 2. Action Buttons (Bottom Right):
        // [rectSideLeft] [rectFire] [rectSideRight] along the bottom row,
        // with [rectUse] directly above [rectFire] and [rectRun] above [rectUse].
        val rightMargin = w - (14f * density)
        val bottomMargin = h - (18f * density)
        val sideW = 58f * density
        val sideH = 68f * density
        val sideGap = 8f * density
        val fireSize = 92f * density

        // Right flanking button (to the right of FIRE)
        rectSideRight.set(
            rightMargin - sideW,
            bottomMargin - sideH,
            rightMargin,
            bottomMargin
        )

        // FIRE button (center of the bottom-right cluster)
        rectFire.set(
            rectSideRight.left - sideGap - fireSize,
            bottomMargin - fireSize,
            rectSideRight.left - sideGap,
            bottomMargin
        )

        // Left flanking button (to the left of FIRE)
        rectSideLeft.set(
            rectFire.left - sideGap - sideW,
            bottomMargin - sideH,
            rectFire.left - sideGap,
            bottomMargin
        )

        // USE button (centered directly above FIRE)
        val useH = 50f * density
        rectUse.set(
            rectFire.left,
            rectFire.top - (10f * density) - useH,
            rectFire.right,
            rectFire.top - (10f * density)
        )

        // RUN Toggle (bar directly above USE)
        val runH = 34f * density
        rectRun.set(
            rectUse.left,
            rectUse.top - (8f * density) - runH,
            rectUse.right,
            rectUse.top - (8f * density)
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

        // 2. Version Watermark
        canvas.drawRoundRect(rectVersion, 6f * density, 6f * density, paintBtnFill)
        canvas.drawText("DOOM v1.3.7 • 4:3 AI-HD (Pixel 10 XL)", rectVersion.centerX(), rectVersion.centerY() + (3.5f * density), paintTextVersion)

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
            drawButton(canvas, rectMapFollow, if (isKeyHeld(DoomBridge.KEY_F)) paintBtnPressed else paintBtnFill, paintStroke, "FLW", paintTextSmall)
            drawButton(canvas, rectMapGrid, if (isKeyHeld(DoomBridge.KEY_G)) paintBtnPressed else paintBtnFill, paintStroke, "GRID", paintTextSmall)
            drawButton(canvas, rectMapMark, if (isKeyHeld(DoomBridge.KEY_M)) paintBtnPressed else paintBtnFill, paintStroke, "MRK", paintTextSmall)
            drawButton(canvas, rectMapClear, if (isKeyHeld(DoomBridge.KEY_C)) paintBtnPressed else paintBtnFill, paintStroke, "CLR", paintTextSmall)
        }

        // Menu Utilities (Only drawn on top-right when DOOM Menu is active)
        if (!rectQuickSave.isEmpty) {
            drawButton(canvas, rectQuickSave, if (isKeyHeld(DoomBridge.KEY_F6)) paintBtnPressed else paintBtnUse, paintStroke, "QSAVE", paintTextSmall)
            drawButton(canvas, rectQuickLoad, if (isKeyHeld(DoomBridge.KEY_F9)) paintBtnPressed else paintBtnUse, paintStroke, "QLOAD", paintTextSmall)
            drawButton(canvas, rectGamma, if (isKeyHeld(DoomBridge.KEY_F11)) paintBtnPressed else paintBtnFill, paintStrokeGold, "GAMMA", paintTextSmall)
            val hdLabel = when (upscaleMode) {
                2 -> "HD: AI+"
                1 -> "HD: TEX"
                else -> "HD: OFF"
            }
            val hdFill = if (upscaleMode > 0) paintBtnRunOn else paintBtnRunOff
            val hdStroke = if (upscaleMode > 0) paintStrokeGreen else paintStroke
            drawButton(canvas, rectUpscale, hdFill, hdStroke, hdLabel, paintTextSmall)
            drawButton(canvas, rectCheats, if (cheatsModalOpen) paintBtnPressed else paintBtnStrafe, paintStrokeGold, "CHEATS", paintTextSmall)
            drawButton(canvas, rectWad, paintBtnTurn, paintStrokeGold, "WAD", paintTextSmall)
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

        // 4. Action Buttons (Right side)
        // FIRE (Center)
        val fireHeld = isKeyHeld(DoomBridge.KEY_FIRE)
        canvas.drawRoundRect(rectFire, 24f * density, 24f * density, if (fireHeld) paintBtnPressed else paintBtnFire)
        canvas.drawRoundRect(rectFire, 24f * density, 24f * density, paintStrokeRed)
        canvas.drawText("FIRE", rectFire.centerX(), rectFire.centerY() + (6f * density), paintTextLarge)

        // Flanking Strafe buttons on either side of FIRE
        drawButton(
            canvas,
            rectSideLeft,
            if (isKeyHeld(DoomBridge.KEY_STRAFE_L)) paintBtnPressed else paintBtnStrafe,
            paintStroke,
            "◀ STRF",
            paintTextSmall
        )
        drawButton(
            canvas,
            rectSideRight,
            if (isKeyHeld(DoomBridge.KEY_STRAFE_R)) paintBtnPressed else paintBtnStrafe,
            paintStroke,
            "STRF ▶",
            paintTextSmall
        )

        // USE (Directly above FIRE — opens doors/switches, never opens menu)
        drawButton(
            canvas,
            rectUse,
            if (isKeyHeld(DoomBridge.KEY_USE)) paintBtnPressed else paintBtnUse,
            paintStroke,
            "USE",
            paintTextLarge
        )

        // RUN Toggle (Above USE)
        drawButton(
            canvas,
            rectRun,
            if (autoRunEnabled) paintBtnRunOn else paintBtnRunOff,
            if (autoRunEnabled) paintStrokeGreen else paintStrokeGold,
            if (autoRunEnabled) "RUN: ON" else "RUN: OFF",
            paintTextMed
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

        // Periodically refresh overlay so top bar buttons appear/disappear immediately on state changes
        postInvalidateDelayed(40)
    }

    private fun drawButton(
        canvas: Canvas,
        rect: RectF,
        fillPaint: Paint,
        strokePaint: Paint,
        label: String,
        textPaint: Paint
    ) {
        val density = resources.displayMetrics.density
        canvas.drawRoundRect(rect, 10f * density, 10f * density, fillPaint)
        canvas.drawRoundRect(rect, 10f * density, 10f * density, strokePaint)
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

        // 1. Auto-Run Toggle Button
        if (rectRun.contains(x, y)) {
            autoRunEnabled = !autoRunEnabled
            DoomBridge.onHapticEvent(1)
            DoomBridge.nativeSetAutoRun(autoRunEnabled)
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

        // 3. Right-Side Action Buttons (FIRE + Strafe L/R flanking buttons + USE)
        if (rectFire.contains(x, y)) {
            // Pure KEY_FIRE (never sends KEY_ENTER!), plus allows dragging thumb to aim while firing
            pointerRoles[pid] = PointerRole.FireAndLook(x, y)
            DoomBridge.onHapticEvent(1)
            DoomBridge.nativeSendKey(1, DoomBridge.KEY_FIRE)
            return
        }
        if (rectSideLeft.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_STRAFE_L)
            return
        }
        if (rectSideRight.contains(x, y)) {
            pressAndHoldKey(pid, DoomBridge.KEY_STRAFE_R)
            return
        }
        if (rectUse.contains(x, y)) {
            // Pure KEY_USE (opens doors/switches/respawns, never triggers menu or level reload!)
            pressAndHoldKey(pid, DoomBridge.KEY_USE)
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
            upscaleMode = (upscaleMode + 2) % 3 // Cycles 2 (AI+) -> 1 (TEX) -> 0 (OFF) -> 2 (AI+)
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
            pointerRoles[pid] = PointerRole.MenuDrag
            DoomBridge.onHapticEvent(1)
            val normX = ((x - viewportLeft) / viewportW).coerceIn(0f, 1f)
            val normY = ((y - viewportTop) / viewportH).coerceIn(0f, 1f)
            DoomBridge.nativeTouchScreen(normX, normY)
            return
        }

        // 7. Left Virtual Stick (Smart Dual-Zone Origin + Dynamic Leash — zero dead travel on direction reversal!)
        val hasStickPointer = pointerRoles.values.any { it is PointerRole.Stick }
        if (!hasStickPointer && (distToStick <= stickRadius * 1.35f || (x < viewportLeft && y > height * 0.35f))) {
            pointerRoles[pid] = PointerRole.Stick
            if (distToStick <= stickRadius * 0.38f) {
                // Touching inner knob: anchor origin to thumb so resting thumb never causes accidental lurch
                stickOriginX = x
                stickOriginY = y
            } else {
                // Touching outer ring: use base center for immediate directional response
                stickOriginX = stickCenterBaseX
                stickOriginY = stickCenterBaseY
            }
            updateStickPosition(x, y)
            return
        }

        // 8. Title Screen / Intermission Direct Touch
        if (x in viewportLeft..(viewportLeft + viewportW) && y in viewportTop..(viewportTop + viewportH)) {
            val normX = ((x - viewportLeft) / viewportW).coerceIn(0f, 1f)
            val normY = ((y - viewportTop) / viewportH).coerceIn(0f, 1f)
            DoomBridge.nativeTouchScreen(normX, normY)
        }

        // 9. Right-Screen Swipe-to-Look
        pointerRoles[pid] = PointerRole.Look(x, y)
    }

    private fun applyDeadzone(value: Float, deadzone: Float): Float {
        val absVal = kotlin.math.abs(value)
        if (absVal <= deadzone) return 0f
        val sign = if (value >= 0f) 1f else -1f
        return (sign * ((absVal - deadzone) / (1f - deadzone))).coerceIn(-1f, 1f)
    }

    private fun updateStickPosition(x: Float, y: Float) {
        var dx = x - stickOriginX
        var dy = y - stickOriginY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        // Dynamic Leash: if thumb drags past stickActiveRadius, pull stickOrigin behind thumb
        // (clamped near base) so reversing thumb direction responds instantaneously!
        if (dist > stickActiveRadius) {
            val angle = atan2(dy.toDouble(), dx.toDouble())
            stickOriginX = x - (stickActiveRadius * cos(angle)).toFloat()
            stickOriginY = y - (stickActiveRadius * sin(angle)).toFloat()

            // Clamp origin drift within 26dp of visual base center
            val maxDrift = stickRadius * 0.36f
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

        // Radial deadzone (8%) + axial anti-veer deadzone so pushing forward doesn't veer sideways
        val normY = applyDeadzone(rawY, 0.08f)
        val horizDeadzone = if (kotlin.math.abs(normY) > 0.55f) 0.15f else 0.08f
        val normX = applyDeadzone(rawX, horizDeadzone)

        // Rotate Mode: X-axis smoothly ROTATES, Y-axis proportionally moves forward/backward
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
                    val viewportH = (viewportW * 3f / 4f).coerceAtMost(height.toFloat())
                    val viewportLeft = (width - viewportW) / 2f
                    val viewportTop = (height - viewportH) / 2f
                    val normX = ((x - viewportLeft) / viewportW).coerceIn(0f, 1f)
                    val normY = ((y - viewportTop) / viewportH).coerceIn(0f, 1f)
                    DoomBridge.nativeTouchScreen(normX, normY)
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
