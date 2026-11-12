package com.id.doom

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

class DoomActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DoomActivity"
    }

    private lateinit var surfaceView: DoomSurfaceView
    private lateinit var touchOverlay: DoomTouchOverlay
    private lateinit var hapticsManager: DoomHapticsManager
    private lateinit var wadManager: WadManager
    private lateinit var updateManager: UpdateManager
    private var isCheckingUpdate = false

    private var pendingExportSaveFile: java.io.File? = null

    private val exportSaveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val fileToExport = pendingExportSaveFile
        pendingExportSaveFile = null
        if (uri != null && fileToExport != null) {
            if (wadManager.exportSaveToUri(fileToExport, uri)) {
                Toast.makeText(this, "Exported ${fileToExport.name} successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to export save file", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Import base-game IWADs through the Storage Access Framework.
    private val openWadLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val displayName = queryDisplayName(it)
            val importedPath = wadManager.importCustomWad(it, displayName)
            if (importedPath != null) {
                Log.i(TAG, "Imported WAD path: $importedPath — restarting engine")
                restartWithSelectedWad()
            } else {
                Toast.makeText(this, "Failed to import WAD: file must be a valid base-game IWAD containing PLAYPAL, COLORMAP, and E1M1/MAP01", Toast.LENGTH_LONG).show()
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
        val quarantined = wadManager.listQuarantinedSaves()
        val options = installed.map { file ->
            val mark = if (file.name.equals(activeName, ignoreCase = true)) " ✔ (Active)" else ""
            "${file.name}$mark"
        }.toMutableList()
        val importIndex = options.size
        options.add("＋ Import .WAD File (DOOM.WAD / DOOM2.WAD)...")
        val recoveryIndex = options.size
        val recCountLabel = if (quarantined.isNotEmpty()) " (${quarantined.size} found)" else ""
        options.add("📦 Recovered Saves Browser / Restore$recCountLabel...")
        val updateIndex = options.size
        val updateBadge = if (UpdateManager.isUpdateAvailable) " ★(Update Available!)" else ""
        options.add("🔄 Check for Updates (v${BuildConfig.VERSION_NAME})$updateBadge...")

        android.app.AlertDialog.Builder(this)
            .setTitle("DOOM WAD & Game Manager")
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which < installed.size -> {
                        val chosen = installed[which]
                        if (!chosen.name.equals(activeName, ignoreCase = true)) {
                            wadManager.setActiveWadName(chosen.name)
                            restartWithSelectedWad()
                        }
                    }
                    which == importIndex -> openWadLauncher.launch(arrayOf("*/*"))
                    which == recoveryIndex -> showQuarantinedSavesDialog()
                    which == updateIndex -> checkManualUpdate()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showQuarantinedSavesDialog() {
        val quarantined = wadManager.listQuarantinedSaves()
        if (quarantined.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Recovered Saves")
                .setMessage("No quarantined saves found in _recovered_v133 or _recovered_pre133.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = quarantined.map { item ->
            "Slot ${item.slotIndex}: \"${item.description}\" [${item.sourceBucket}]"
        }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Select Recovered Save Slot")
            .setItems(labels) { _, idx ->
                val selected = quarantined[idx]
                showRecoveredSaveActionDialog(selected)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRecoveredSaveActionDialog(item: WadManager.QuarantinedSaveSlot) {
        val actions = arrayOf(
            "Restore to an IWAD Save Slot...",
            "Export Save File (.dsg) via Files..."
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Slot ${item.slotIndex}: \"${item.description}\"")
            .setItems(actions) { _, which ->
                if (which == 0) {
                    showSelectTargetWadForRestoreDialog(item)
                } else {
                    pendingExportSaveFile = item.file
                    exportSaveLauncher.launch("doomsav${item.slotIndex}_${item.description.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.dsg")
                }
            }
            .setNegativeButton("Back") { _, _ -> showQuarantinedSavesDialog() }
            .show()
    }

    private fun showSelectTargetWadForRestoreDialog(item: WadManager.QuarantinedSaveSlot) {
        val installed = wadManager.listInstalledWads()
        val activeName = wadManager.getActiveWadName()
        val wadNames = installed.map { it.name }
        val labels = wadNames.map { name ->
            if (name.equals(activeName, ignoreCase = true)) "$name (Active)" else name
        }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Choose Destination IWAD")
            .setItems(labels) { _, wIdx ->
                val targetWad = wadNames[wIdx]
                showSelectTargetSlotForRestoreDialog(item, targetWad)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSelectTargetSlotForRestoreDialog(item: WadManager.QuarantinedSaveSlot, targetWad: String) {
        val slotLabels = (0..5).map { slot ->
            val existing = wadManager.getTargetSaveFile(targetWad, slot)
            val status = if (existing.exists()) "(Occupied)" else "(Empty)"
            val rec = if (slot == item.slotIndex) " ★Original Slot" else ""
            "Slot $slot $status$rec"
        }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Restore to $targetWad")
            .setItems(slotLabels) { _, targetSlot ->
                val destFile = wadManager.getTargetSaveFile(targetWad, targetSlot)
                if (destFile.exists()) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Slot $targetSlot Already Occupied")
                        .setMessage("Target $targetWad already has a save in Slot $targetSlot. Overwrite it (a .bak backup of the current slot will be preserved alongside the quarantined original)?")
                        .setPositiveButton("Backup & Overwrite") { _, _ ->
                            val ok = wadManager.restoreQuarantinedSave(item, targetWad, targetSlot, backupExisting = true)
                            if (ok) {
                                Toast.makeText(this, "Restored to $targetWad Slot $targetSlot (previous slot backed up)", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this, "Failed to restore save slot", Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    val ok = wadManager.restoreQuarantinedSave(item, targetWad, targetSlot, backupExisting = false)
                    if (ok) {
                        Toast.makeText(this, "Restored to $targetWad Slot $targetSlot", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Failed to restore save slot", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the game visible across the full landscape display.
        setupEdgeToEdge()


        // Route the Android Back gesture to the DOOM menu.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                DoomBridge.nativeSendKey(1, DoomBridge.KEY_ESCAPE)
                window.decorView.postDelayed({
                    DoomBridge.nativeSendKey(0, DoomBridge.KEY_ESCAPE)
                }, 60)
            }
        })

        hapticsManager = DoomHapticsManager(this)
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
        DoomBridge.updateCheckListener = {
            runOnUiThread {
                checkManualUpdate()
            }
        }

        updateManager = UpdateManager(this)
        UpdateManager.onUpdateAvailabilityChanged = { available ->
            runOnUiThread {
                if (::touchOverlay.isInitialized) {
                    touchOverlay.isUpdateAvailable = available
                }
            }
        }

        val recoveryPrefs = getSharedPreferences("doom_engine_recovery", Context.MODE_PRIVATE)
        wadManager = WadManager(this)
        val iwadPath = if (recoveryPrefs.getBoolean("crashed_on_boot", false)) {
            Log.w(TAG, "Previous launch detected native crash. Restoring pristine bundled doom1.wad.")
            recoveryPrefs.edit().putBoolean("crashed_on_boot", false).commit()
            wadManager.forceRestoreDefaultWad()
        } else {
            wadManager.setupDefaultWad()
        }

        // Center the 4:3 game surface beneath the touch controls.
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
            isUpdateAvailable = UpdateManager.isUpdateAvailable
        }

        rootLayout.addView(surfaceView)
        rootLayout.addView(touchOverlay)
        setContentView(rootLayout)

        DoomBridge.firstFrameListener = { frameSession ->
            runOnUiThread {
                Log.i(TAG, "Native engine rendered first frame successfully for session $frameSession — disarming crash guard")
                recoveryPrefs.edit().putBoolean("crashed_on_boot", false).commit()
            }
        }

        // Two-phase session-scoped crash guard:
        // Phase 1: Query whether nativeInit will start a new thread (>0) or reconnect to a running one (0).
        // Durably persist crashed_on_boot = true via synchronous commit() BEFORE spawning the native thread.
        Log.i(TAG, "Initializing DOOM engine with IWAD: $iwadPath")
        val preparedSession = DoomBridge.nativePrepareInit()
        if (preparedSession > 0) {
            Log.i(TAG, "Durably arming crash guard for new DOOM engine session $preparedSession before thread start")
            recoveryPrefs.edit().putBoolean("crashed_on_boot", true).commit()
        } else {
            Log.i(TAG, "Reconnecting to existing running DOOM engine thread — crash guard remains disarmed")
        }

        // Phase 2: Spawn or resume the native engine thread
        DoomBridge.nativeInit(iwadPath, wadManager.saveDirectory.absolutePath)

        // Asynchronously check for GitHub Releases OTA updates without blocking engine startup
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                updateManager.checkForUpdates(manual = false) { result ->
                    result.onSuccess { updateInfo ->
                        if (updateInfo != null && !isFinishing && !isDestroyed) {
                            showUpdatePrompt(updateInfo)
                        }
                    }
                }
            }
        }, 3500)
    }

    override fun onResume() {
        super.onResume()
        DoomBridge.nativeSetPaused(false)
        val pending = updateManager.pendingInstallFile
        if (pending != null && pending.exists()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                updateManager.pendingInstallFile = null
                updateManager.launchInstallIntent(this, pending)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        heldGamepadButtons.clear()
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


    private val heldGamepadButtons = mutableSetOf<Pair<Int, Int>>()

    private fun gamepadKey(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> DoomBridge.KEY_USE
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> DoomBridge.KEY_FIRE
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> DoomBridge.KEY_RSHIFT
        KeyEvent.KEYCODE_BUTTON_START -> DoomBridge.KEY_ESCAPE
        KeyEvent.KEYCODE_BUTTON_SELECT -> DoomBridge.KEY_TAB
        else -> null
    }

    private fun handleGamepadKey(keyCode: Int, event: KeyEvent?, pressed: Boolean): Boolean {
        if (event == null || (!event.isFromSource(InputDevice.SOURCE_GAMEPAD) &&
                !event.isFromSource(InputDevice.SOURCE_JOYSTICK))) return false
        val doomKey = gamepadKey(keyCode) ?: return false
        val wasHeld = heldGamepadButtons.any { gamepadKey(it.second) == doomKey }
        val button = event.deviceId to keyCode
        if (pressed) heldGamepadButtons.add(button) else heldGamepadButtons.remove(button)
        val isHeld = heldGamepadButtons.any { gamepadKey(it.second) == doomKey }
        // Ignore key repeats and keep FIRE/RUN held while either shoulder button is down.
        if (wasHeld != isHeld) DoomBridge.nativeSendKey(if (isHeld) 1 else 0, doomKey)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return handleGamepadKey(keyCode, event, true) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return handleGamepadKey(keyCode, event, false) || super.onKeyUp(keyCode, event)
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

    private fun checkManualUpdate() {
        if (isCheckingUpdate) return
        isCheckingUpdate = true

        val density = resources.displayMetrics.density
        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
        }
        val message = TextView(this).apply {
            text = "Checking GitHub for updates..."
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(progressBar)
            addView(message)
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("DOOM OTA Updater")
            .setView(layout)
            .setCancelable(false)
            .create()
        dialog.show()

        updateManager.checkForUpdates(manual = true) { result ->
            isCheckingUpdate = false
            if (!isFinishing && !isDestroyed) {
                dialog.dismiss()
                result.fold(
                    onSuccess = { updateInfo ->
                        if (updateInfo != null) {
                            showUpdatePrompt(updateInfo)
                        } else {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("DOOM is Up to Date")
                                .setMessage("You are running the latest version of DOOM:\nv${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    },
                    onFailure = { error ->
                        android.app.AlertDialog.Builder(this)
                            .setTitle("Update Check Failed")
                            .setMessage("Could not connect to GitHub Releases:\n${error.localizedMessage ?: error.message}\n\nPlease check your internet connection.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                )
            }
        }
    }

    private fun showUpdatePrompt(updateInfo: UpdateManager.UpdateInfo) {
        val notesPreview = updateInfo.releaseNotes.trim()
            .lines()
            .take(10)
            .joinToString("\n")
            .take(500)

        android.app.AlertDialog.Builder(this)
            .setTitle("⚡ DOOM Update Available!")
            .setMessage(
                "A newer version of DOOM is available on GitHub Releases:\n\n" +
                "• Current Version: v${BuildConfig.VERSION_NAME}\n" +
                "• Latest Version:  ${updateInfo.tagName} (${UpdateManager.formatBytes(updateInfo.apkSize)})\n\n" +
                (if (notesPreview.isNotBlank()) "Changelog:\n$notesPreview\n\n" else "") +
                "Would you like to download and install this update now?"
            )
            .setPositiveButton("Update Now") { _, _ ->
                startApkDownload(updateInfo)
            }
            .setNegativeButton("Later", null)
            .setNeutralButton("View on GitHub") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.htmlUrl)))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to open release URL", e)
                }
            }
            .show()
    }

    private fun startApkDownload(updateInfo: UpdateManager.UpdateInfo) {
        var isCanceled = false
        val density = resources.displayMetrics.density

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        val statusText = TextView(this).apply {
            text = "Connecting to GitHub Releases..."
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(progressBar)
            addView(statusText)
        }

        val downloadDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Downloading DOOM ${updateInfo.tagName}")
            .setView(layout)
            .setNegativeButton("Cancel") { _, _ ->
                isCanceled = true
            }
            .setCancelable(false)
            .create()
        downloadDialog.show()

        val totalSize = updateInfo.apkSize
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val downloadedFile = updateManager.downloadApk(
                    updateInfo = updateInfo,
                    isCanceled = { isCanceled },
                    onProgress = { downloaded, total ->
                        val totalToUse = if (total > 0) total else totalSize
                        val pct = if (totalToUse > 0) ((downloaded * 100) / totalToUse).toInt() else 0
                        val dlStr = UpdateManager.formatBytes(downloaded)
                        val totStr = UpdateManager.formatBytes(totalToUse)
                        runOnUiThread {
                            if (!isCanceled && !isFinishing && !isDestroyed) {
                                progressBar.progress = pct
                                statusText.text = "$dlStr / $totStr ($pct%)"
                            }
                        }
                    }
                )

                runOnUiThread {
                    if (!isCanceled && !isFinishing && !isDestroyed) {
                        downloadDialog.dismiss()
                        updateManager.installApk(this, downloadedFile)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isCanceled && !isFinishing && !isDestroyed) {
                        downloadDialog.dismiss()
                        if (e !is CancellationException) {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("Download Failed")
                                .setMessage("Failed to download update APK:\n${e.localizedMessage ?: e.message}")
                                .setPositiveButton("Retry") { _, _ -> startApkDownload(updateInfo) }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DoomBridge.hapticListener = null
        DoomBridge.quitListener = null
        DoomBridge.wadPickerListener = null
        DoomBridge.updateCheckListener = null
        DoomBridge.firstFrameListener = null
        UpdateManager.onUpdateAvailabilityChanged = null
        DoomBridge.nativeStop()
    }
}
