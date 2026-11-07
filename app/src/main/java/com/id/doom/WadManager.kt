package com.id.doom

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
     * Validates whether a file is a playable, well-formed DOOM base-game IWAD.
     * Enforces:
     * - Minimum size of 12 bytes
     * - Magic header is strictly "IWAD" (stand-alone base game container)
     * - Bounded lump count (50 to 50,000; bounds memory consumption on UI thread)
     * - Lump directory bounds within file size
     * - Validates individual lump offsets and lengths
     * - Requires mandatory base-game resources: PLAYPAL, COLORMAP, PNAMES, and at least one mission map (E1M1 or MAP01)
     */
    fun isValidWadFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        val fileLength = file.length()
        if (fileLength < 12L) return false

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(12)
                raf.readFully(header)

                val magic = String(header, 0, 4, Charsets.US_ASCII)
                if (magic != "IWAD") {
                    Log.w(TAG, "File ${file.name} is not an IWAD (magic: '$magic')")
                    return false
                }

                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val numLumps = buf.getInt(4)
                val infoTableOfs = buf.getInt(8)

                if (numLumps < 50 || numLumps > 50000) {
                    Log.w(TAG, "File ${file.name} invalid lump count: $numLumps")
                    return false
                }
                if (infoTableOfs < 12) {
                    Log.w(TAG, "File ${file.name} invalid directory offset: $infoTableOfs")
                    return false
                }
                val dirBytes = numLumps.toLong() * 16L
                val dirEnd = infoTableOfs.toLong() + dirBytes
                if (dirEnd > fileLength) {
                    Log.w(TAG, "File ${file.name} directory extends beyond EOF: $dirEnd > $fileLength")
                    return false
                }

                // Read lump directory entries to check required base-game resources
                raf.seek(infoTableOfs.toLong())
                val dirBuffer = ByteArray(dirBytes.toInt())
                raf.readFully(dirBuffer)
                val dirWrap = ByteBuffer.wrap(dirBuffer).order(ByteOrder.LITTLE_ENDIAN)

                var hasPlaypal = false
                var hasColormap = false
                var hasPnames = false
                var hasMap = false

                for (i in 0 until numLumps) {
                    val entryOffset = i * 16
                    val lumpPos = dirWrap.getInt(entryOffset).toLong() and 0xFFFFFFFFL
                    val lumpSize = dirWrap.getInt(entryOffset + 4).toLong() and 0xFFFFFFFFL

                    if (lumpPos + lumpSize > fileLength) {
                        Log.w(TAG, "File ${file.name} lump $i ($lumpPos + $lumpSize) extends beyond EOF ($fileLength)")
                        return false
                    }

                    val nameBytes = ByteArray(8)
                    System.arraycopy(dirBuffer, entryOffset + 8, nameBytes, 0, 8)
                    val zeroIdx = nameBytes.indexOf(0.toByte())
                    val lumpName = if (zeroIdx >= 0) {
                        String(nameBytes, 0, zeroIdx, Charsets.US_ASCII).trim()
                    } else {
                        String(nameBytes, Charsets.US_ASCII).trim()
                    }.uppercase()

                    when (lumpName) {
                        "PLAYPAL" -> hasPlaypal = true
                        "COLORMAP" -> hasColormap = true
                        "PNAMES" -> hasPnames = true
                        "E1M1", "MAP01" -> hasMap = true
                    }
                }

                if (!hasPlaypal || !hasColormap || !hasPnames || !hasMap) {
                    Log.w(TAG, "File ${file.name} missing essential base resources: PLAYPAL=$hasPlaypal, COLORMAP=$hasColormap, PNAMES=$hasPnames, MAP=$hasMap")
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
     * Unconditionally restores a pristine copy of bundled shareware doom1.wad from APK assets.
     */
    fun forceRestoreDefaultWad(): String {
        val defaultFile = File(wadDirectory, DEFAULT_WAD)
        if (defaultFile.exists()) {
            defaultFile.delete()
        }
        context.assets.open("wads/$DEFAULT_WAD").use { input ->
            FileOutputStream(defaultFile).use { output ->
                input.copyTo(output)
            }
        }
        setActiveWadName(DEFAULT_WAD)
        Log.i(TAG, "Force-restored clean bundled $DEFAULT_WAD (${defaultFile.length()} bytes)")
        return defaultFile.absolutePath
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
            Log.w(TAG, "Active WAD '$activeName' is invalid or missing essential resources, reverting to $DEFAULT_WAD")
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
     * Streams into a temporary file, validates complete IWAD structure and required resources,
     * and atomically promotes via atomic filesystem rename without pre-deleting the original.
     * Protects bundled doom1.wad recovery copy from ever being overwritten.
     */
    fun importCustomWad(uri: Uri, displayName: String): String? {
        val sanitized = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_')
        val rawBase = if (sanitized.endsWith(".wad", ignoreCase = true)) sanitized else "$sanitized.wad"
        // Protect bundled doom1.wad from being overwritten by an imported file
        val cleanName = if (rawBase.equals(DEFAULT_WAD, ignoreCase = true)) "custom_doom1.wad" else rawBase

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
                Log.e(TAG, "Imported file $displayName is not a valid DOOM IWAD. Deleting temporary file.")
                tmpFile.delete()
                return null
            }

            // Atomic filesystem move: replaces destFile in a single POSIX rename syscall without deletion gap
            try {
                Files.move(
                    tmpFile.toPath(),
                    destFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: Exception) {
                // Fallback to standard replace if filesystem does not support atomic move
                Files.move(
                    tmpFile.toPath(),
                    destFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

            setActiveWadName(destFile.name)
            Log.i(TAG, "Successfully imported and validated custom IWAD: ${destFile.absolutePath}")
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
