package com.rdr.youtube2

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoDownloadManager {

    data class DownloadResult(
        val success: Boolean,
        val uri: Uri? = null,
        val fileName: String = "",
        val errorMessage: String = ""
    )

    private const val APP_FOLDER = "Youtube2"
    private const val MIME_MP4 = "video/mp4"

    fun downloadMp4(
        context: Context,
        videoId: String,
        title: String,
        candidate: YouTubeStreamResolver.StreamCandidate
    ): DownloadResult {
        val appContext = context.applicationContext
        val fileName = buildFileName(videoId, title, candidate.qualityLabel)
        val targetUri = runCatching { Uri.parse(candidate.url) }.getOrNull()
            ?: return DownloadResult(success = false, errorMessage = "URL de descarga invalida")
        if (targetUri.scheme?.startsWith("http", ignoreCase = true) != true) {
            return DownloadResult(success = false, errorMessage = "URL de descarga invalida")
        }

        val request = DownloadManager.Request(targetUri)
            .setTitle("Descargando video")
            .setDescription(fileName)
            .setMimeType(MIME_MP4)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setVisibleInDownloadsUi(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_MOVIES,
                "$APP_FOLDER/$fileName"
            )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            request.allowScanningByMediaScanner()
        }

        if (candidate.userAgent.isNotBlank()) {
            request.addRequestHeader("User-Agent", candidate.userAgent)
        }
        candidate.requestHeaders.forEach { (key, value) ->
            if (
                key.isNotBlank() &&
                value.isNotBlank() &&
                !key.equals("User-Agent", ignoreCase = true)
            ) {
                request.addRequestHeader(key, value)
            }
        }

        val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return DownloadResult(
                success = false,
                errorMessage = "No se pudo acceder al gestor de descargas"
            )

        return try {
            val downloadId = manager.enqueue(request)
            val uri = Uri.parse("content://downloads/my_downloads/$downloadId")
            DownloadResult(success = true, uri = uri, fileName = fileName)
        } catch (t: Throwable) {
            DownloadResult(
                success = false,
                errorMessage = t.message ?: "No se pudo iniciar la descarga"
            )
        }
    }

    private fun buildFileName(videoId: String, title: String, qualityLabel: String): String {
        val safeTitle = sanitizeFileComponent(title).ifBlank { "video_$videoId" }
        val quality = sanitizeFileComponent(qualityLabel.lowercase(Locale.ROOT)).ifBlank { "mp4" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${safeTitle}_${quality}_$stamp.mp4"
    }

    private fun sanitizeFileComponent(input: String): String {
        return input
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(64)
    }
}
