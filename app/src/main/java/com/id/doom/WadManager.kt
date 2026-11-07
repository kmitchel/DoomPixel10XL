package com.id.doom

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class WadManager(private val context: Context) {

    companion object {
        private const val TAG = "WadManager"
        private const val DEFAULT_WAD = "doom1.wad"
        private const val PREFS_NAME = "doom_wad_prefs"
        private const val KEY_ACTIVE_WAD = "active_wad_name"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val wadDirectory: File
        get() = File(context.filesDir, "wads").apply { if (!exists()) mkdirs() }

    val saveDirectory: File
        get() = File(context.filesDir, "saves").apply { if (!exists()) mkdirs() }

    /**
     * Ensures default shareware doom1.wad exists in app internal storage
     * and returns the currently active IWAD path.
     */
    fun setupDefaultWad(): String {
        val defaultFile = File(wadDirectory, DEFAULT_WAD)
        if (!defaultFile.exists() || defaultFile.length() == 0L) {
            Log.i(TAG, "Extracting bundled $DEFAULT_WAD from assets...")
            context.assets.open("wads/$DEFAULT_WAD").use { input ->
                FileOutputStream(defaultFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Extracted $DEFAULT_WAD (${defaultFile.length()} bytes)")
        }

        val activeName = prefs.getString(KEY_ACTIVE_WAD, DEFAULT_WAD) ?: DEFAULT_WAD
        val activeFile = File(wadDirectory, activeName)
        return if (activeFile.exists() && activeFile.length() > 0L) {
            activeFile.absolutePath
        } else {
            defaultFile.absolutePath
        }
    }

    fun getActiveWadName(): String {
        return prefs.getString(KEY_ACTIVE_WAD, DEFAULT_WAD) ?: DEFAULT_WAD
    }

    fun setActiveWadName(fileName: String) {
        prefs.edit().putString(KEY_ACTIVE_WAD, fileName).apply()
    }

    fun listInstalledWads(): List<File> {
        return wadDirectory.listFiles { file ->
            file.isFile && file.name.endsWith(".wad", ignoreCase = true)
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    /**
     * Imports a user-selected WAD file via Android Storage Access Framework (SAF).
     */
    fun importCustomWad(uri: Uri, displayName: String): String? {
        return try {
            val cleanName = if (displayName.endsWith(".wad", ignoreCase = true)) displayName else "$displayName.wad"
            val destFile = File(wadDirectory, cleanName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            setActiveWadName(destFile.name)
            Log.i(TAG, "Imported custom WAD to: ${destFile.absolutePath}")
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import custom WAD", e)
            null
        }
    }
}
