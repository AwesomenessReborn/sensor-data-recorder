package com.example.sensor_recorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ExportUtils {
    private const val TAG = "ExportUtils"

    /**
     * Creates a ZIP file from the recording directory
     * Returns the File object for the created ZIP
     */
    fun zipRecordingDirectory(context: Context, recordingDir: File): File? {
        return try {
            val zipFileName = "${recordingDir.name}.zip"
            val cacheDir = context.cacheDir
            val zipFile = File(cacheDir, zipFileName)

            // Delete old ZIP if it exists
            if (zipFile.exists()) {
                zipFile.delete()
            }

            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                recordingDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        FileInputStream(file).use { input ->
                            val entry = ZipEntry(file.name)
                            zipOut.putNextEntry(entry)
                            input.copyTo(zipOut)
                            zipOut.closeEntry()
                        }
                    }
                }
            }

            Log.d(TAG, "Created ZIP: ${zipFile.absolutePath}")
            zipFile
        } catch (e: Exception) {
            Log.e(TAG, "Error creating ZIP file", e)
            null
        }
    }

    /**
     * Share recording via Android share sheet
     */
    fun shareRecording(context: Context, recordingDir: File): Boolean {
        return try {
            val zipFile = zipRecordingDirectory(context, recordingDir)
            if (zipFile == null) {
                Log.e(TAG, "Failed to create ZIP file")
                return false
            }

            val uri = FileProvider.getUriForFile(
                context,
                "com.example.sensor_recorder.fileprovider",
                zipFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "IMU Recording: ${recordingDir.name}")
                putExtra(Intent.EXTRA_TEXT, "IMU sensor recording from Sensor Recorder app")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(shareIntent, "Export Recording"))
            Log.d(TAG, "Share sheet opened successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing recording", e)
            false
        }
    }

    /**
     * Get a content URI for a recording ZIP file
     */
    fun getRecordingUri(context: Context, recordingDir: File): Uri? {
        return try {
            val zipFile = zipRecordingDirectory(context, recordingDir)
            if (zipFile == null) {
                Log.e(TAG, "Failed to create ZIP file")
                return null
            }

            FileProvider.getUriForFile(
                context,
                "com.example.sensor_recorder.fileprovider",
                zipFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recording URI", e)
            null
        }
    }

    /**
     * Clean up old ZIP files from cache directory
     */
    fun cleanupOldZips(context: Context) {
        try {
            val cacheDir = context.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".zip")) {
                    val deleted = file.delete()
                    Log.d(TAG, "Deleted old ZIP: ${file.name}, success: $deleted")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old ZIPs", e)
        }
    }
}
