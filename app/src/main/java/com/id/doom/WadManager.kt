package com.id.doom

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
     * Validates whether a file is a well-formed DOOM WAD (IWAD or PWAD).
     * Checks:
     * - Minimum size of 12 bytes
     * - Magic header: "IWAD" or "PWAD"
     * - Valid numlumps (0 to 500,000)
     * - Directory offset inside file bounds: infotableofs >= 12 and infotableofs + numlumps * 16 <= fileSize
     */
    fun isValidWadFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        val fileLength = file.length()
        if (fileLength < 12L) return false

        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(12)
                var bytesRead = 0
                while (bytesRead < 12) {
                    val r = input.read(header, bytesRead, 12 - bytesRead)
                    if (r < 0) return false
                    bytesRead += r
                }

                val magic = String(header, 0, 4, Charsets.US_ASCII)
                if (magic != "IWAD" && magic != "PWAD") {
                    Log.w(TAG, "File ${file.name} header magic is not IWAD/PWAD: '$magic'")
                    return false
                }

                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val numLumps = buf.getInt(4)
                val infoTableOfs = buf.getInt(8)

                if (numLumps < 0 || numLumps > 500000) {
                    Log.w(TAG, "File ${file.name} invalid lump count: $numLumps")
                    return false
                }
                if (infoTableOfs < 12) {
                    Log.w(TAG, "File ${file.name} invalid directory offset: $infoTableOfs")
                    return false
                }
                val dirEnd = infoTableOfs.toLong() + (numLumps.toLong() * 16L)
                if (dirEnd > fileLength) {
                    Log.w(TAG, "File ${file.name} directory extends beyond EOF: $dirEnd > $fileLength")
                    return false
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating WAD file ${file.name}", e)
            false
        }
    }

    /**
     * Ensures default shareware doom1.wad exists in app internal storage
     * and returns the currently active IWAD path.
     * Safely validates the active WAD and reverts to doom1.wad if corrupted or invalid.
     */
    fun setupDefaultWad(): String {
        val defaultFile = File(wadDirectory, DEFAULT_WAD)
        if (!defaultFile.exists() || !isValidWadFile(defaultFile)) {
            Log.i(TAG, "Extracting bundled $DEFAULT_WAD from assets...")
            if (defaultFile.exists()) {
                defaultFile.delete()
            }
            context.assets.open("wads/$DEFAULT_WAD").use { input ->
                FileOutputStream(defaultFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Extracted $DEFAULT_WAD (${defaultFile.length()} bytes)")
        }

        val activeName = prefs.getString(KEY_ACTIVE_WAD, DEFAULT_WAD) ?: DEFAULT_WAD
        val activeFile = File(wadDirectory, activeName)
        return if (isValidWadFile(activeFile)) {
            activeFile.absolutePath
        } else {
            Log.w(TAG, "Active WAD '$activeName' is invalid or missing, reverting to $DEFAULT_WAD")
            setActiveWadName(DEFAULT_WAD)
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
            file.isFile && file.name.endsWith(".wad", ignoreCase = true) && isValidWadFile(file)
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    /**
     * Imports a user-selected WAD file via Android Storage Access Framework (SAF).
     * Streams into a temporary file, validates WAD header and directory bounds,
     * and atomically promotes only upon successful validation.
     */
    fun importCustomWad(uri: Uri, displayName: String): String? {
        val sanitized = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_')
        val cleanName = if (sanitized.endsWith(".wad", ignoreCase = true)) sanitized else "$sanitized.wad"
        val tmpFile = File(wadDirectory, "$cleanName.tmp")
        val destFile = File(wadDirectory, cleanName)

        return try {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Log.e(TAG, "Failed to open input stream for $uri")
                return null
            }

            if (!isValidWadFile(tmpFile)) {
                Log.e(TAG, "Imported file $displayName is not a valid DOOM WAD. Deleting temporary file.")
                tmpFile.delete()
                return null
            }

            if (destFile.exists()) {
                destFile.delete()
            }
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tmpFile.delete()
            }

            setActiveWadName(destFile.name)
            Log.i(TAG, "Successfully imported and validated custom WAD: ${destFile.absolutePath}")
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import custom WAD: $displayName", e)
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
            null
        }
    }
}
