package com.id.doom

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class DoomActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DoomActivity"
    }

    private lateinit var surfaceView: DoomSurfaceView
    private lateinit var touchOverlay: DoomTouchOverlay
    private lateinit var hapticsManager: PixelHapticsManager
    private lateinit var wadManager: WadManager

    // Storage Access Framework contract to import external IWADs/PWADs (DOOM.WAD, DOOM2.WAD, etc.)
    private val openWadLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val displayName = queryDisplayName(it)
            val importedPath = wadManager.importCustomWad(it, displayName)
            if (importedPath != null) {
                Log.i(TAG, "Imported WAD path: $importedPath — restarting engine")
                restartWithSelectedWad()
            } else {
                Toast.makeText(this, "Failed to import WAD: file is not a valid DOOM WAD (must start with IWAD or PWAD)", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = cursor.getString(index)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve DISPLAY_NAME for $uri", e)
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        }
        return name?.takeIf { it.isNotBlank() } ?: "custom.wad"
    }

    private fun restartWithSelectedWad() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        finish()
        if (intent != null) {
            startActivity(intent)
        }
        Runtime.getRuntime().exit(0)
    }

    private fun showWadSelectorDialog() {
        val installed = wadManager.listInstalledWads()
        val activeName = wadManager.getActiveWadName()
        val options = installed.map { file ->
            val mark = if (file.name.equals(activeName, ignoreCase = true)) " ✔ (Active)" else ""
            "${file.name}$mark"
        }.toMutableList()
        options.add("＋ Import .WAD File (DOOM.WAD / DOOM2.WAD)...")

        android.app.AlertDialog.Builder(this)
            .setTitle("Select or Import DOOM WAD")
            .setItems(options.toTypedArray()) { _, which ->
                if (which < installed.size) {
                    val chosen = installed[which]
                    if (!chosen.name.equals(activeName, ignoreCase = true)) {
                        wadManager.setActiveWadName(chosen.name)
                        restartWithSelectedWad()
                    }
                } else {
                    openWadLauncher.launch(arrayOf("*/*"))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Configure Edge-to-Edge, Keep Screen On, and Camera Cutout for Pixel 10 XL
        setupEdgeToEdge()

        // 2. Request 120Hz LTPO High Refresh Rate
        requestHighRefreshRate()

        // 3. Intercept Android system Back gesture to toggle DOOM Menu instead of killing game
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                DoomBridge.nativeSendKey(1, DoomBridge.KEY_ESCAPE)
                window.decorView.postDelayed({
                    DoomBridge.nativeSendKey(0, DoomBridge.KEY_ESCAPE)
                }, 60)
            }
        })

        // 4. Initialize Haptics, Quit Callback, and WAD Management
        hapticsManager = PixelHapticsManager(this)
        DoomBridge.hapticListener = { typeId ->
            hapticsManager.triggerHaptic(typeId)
        }
        DoomBridge.quitListener = {
            runOnUiThread {
                finishAndRemoveTask()
            }
        }
        DoomBridge.wadPickerListener = {
            runOnUiThread {
                showWadSelectorDialog()
            }
        }

        wadManager = WadManager(this)
        val iwadPath = wadManager.setupDefaultWad()

        // 5. Construct Layout: 4:3 Pillarboxed SurfaceView centered in OLED black root, TouchOverlay on top
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        surfaceView = DoomSurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        }

        touchOverlay = DoomTouchOverlay(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        rootLayout.addView(surfaceView)
        rootLayout.addView(touchOverlay)
        setContentView(rootLayout)

        // 6. Launch native engine thread
        Log.i(TAG, "Launching DOOM engine with IWAD: $iwadPath")
        DoomBridge.nativeInit(iwadPath, wadManager.saveDirectory.absolutePath)
    }

    override fun onResume() {
        super.onResume()
        DoomBridge.nativeSetPaused(false)
    }

    override fun onPause() {
        super.onPause()
        DoomBridge.nativeSetPaused(true)
    }

    private fun setupEdgeToEdge() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun requestHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = this.display
            if (display != null) {
                // Find 120Hz display mode if supported
                val targetMode = display.supportedModes.find { mode ->
                    mode.refreshRate >= 119.0f
                }
                if (targetMode != null) {
                    val params = window.attributes
                    params.preferredDisplayModeId = targetMode.modeId
                    window.attributes = params
                    Log.i(TAG, "Requested 120Hz mode ID: ${targetMode.modeId} (${targetMode.refreshRate} Hz)")
                }
            }
        }
    }

    // Physical Gamepad Support
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.source?.and(InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            event?.source?.and(InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    DoomBridge.nativeSendKey(1, DoomBridge.KEY_USE)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> {
                    DoomBridge.nativeSendKey(1, DoomBridge.KEY_FIRE)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> {
                    DoomBridge.nativeSendKey(1, DoomBridge.KEY_RSHIFT)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_START -> {
                    DoomBridge.nativeSendKey(1, DoomBridge.KEY_ESCAPE)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    DoomBridge.nativeSendKey(1, DoomBridge.KEY_TAB)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.source?.and(InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            event?.source?.and(InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    DoomBridge.nativeSendKey(0, DoomBridge.KEY_USE)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> {
                    DoomBridge.nativeSendKey(0, DoomBridge.KEY_FIRE)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> {
                    DoomBridge.nativeSendKey(0, DoomBridge.KEY_RSHIFT)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_START -> {
                    DoomBridge.nativeSendKey(0, DoomBridge.KEY_ESCAPE)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    DoomBridge.nativeSendKey(0, DoomBridge.KEY_TAB)
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            val leftX = event.getAxisValue(MotionEvent.AXIS_X)
            val leftY = event.getAxisValue(MotionEvent.AXIS_Y)
            val rightX = event.getAxisValue(MotionEvent.AXIS_Z)

            DoomBridge.nativeSendAnalog(forward = -leftY, strafe = leftX, turn = rightX)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        DoomBridge.nativeStop()
    }
}
