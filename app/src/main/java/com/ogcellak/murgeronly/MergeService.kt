package com.ogcellak.murgeronly

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MergeService : Service() {
    companion object {
        const val START = "START"
        const val PROGRESS = "com.ogcellak.murgeronly.MERGE_PROGRESS"
        private const val CHANNEL = "merge"
        private const val NOTIFICATION_ID = 7001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Video merging", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == START) {
            startForeground(NOTIFICATION_ID, notice("Checking video formats…", 0))
            val uris = intent.getStringArrayListExtra("uris") ?: arrayListOf()
            getSharedPreferences("merge_state", MODE_PRIVATE).edit()
                .putBoolean("running", true)
                .putInt("progress", 0)
                .putLong("started", System.currentTimeMillis())
                .putString("message", "Checking video formats…")
                .apply()

            scope.launch {
                try {
                    Merger(this@MergeService).run(uris) { p, message -> notifyState(p, message) }
                } catch (t: Throwable) {
                    notifyState(0, "Merge failed: " + (t.message ?: t.javaClass.simpleName).take(180), false)
                } finally {
                    delay(2200)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notice(message: String, progress: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Merger Only")
            .setContentText(message)
            .setOngoing(progress in 0..99)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .build()

    private fun notifyState(progress: Int, message: String, running: Boolean = progress < 100) {
        val p = progress.coerceIn(0, 100)
        getSharedPreferences("merge_state", MODE_PRIVATE).edit()
            .putBoolean("running", running)
            .putInt("progress", p)
            .putString("message", message)
            .apply()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notice(message, p))
        sendBroadcast(Intent(PROGRESS).setPackage(packageName).putExtra("progress", p).putExtra("message", message))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private class Merger(private val context: android.content.Context) {
    suspend fun run(items: ArrayList<String>, progress: (Int, String) -> Unit) {
        require(items.size >= 2) { "Select at least two videos." }

        // Check that each URI can be opened, but don't pre-parse with Android MediaExtractor:
        // that would reject containers which Media3's own extractors can handle.
        val mediaItems = withContext(Dispatchers.IO) {
            items.mapIndexed { index, rawUri ->
                progress((index * 3).coerceAtMost(12), "Checking video ${index + 1}/${items.size}…")
                val uri = Uri.parse(rawUri)
                val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                    ?: error("Video ${index + 1} cannot be opened. Re-select the file and try again.")
                descriptor.use { }
                EditedMediaItem.Builder(MediaItem.fromUri(uri)).build()
            }
        }

        // One sequential sequence yields one continuous video. Transformer normalizes input
        // codecs/containers to H.264/AAC MP4 rather than relying on byte-for-byte stream copy.
        val sequence = EditedMediaItemSequence.Builder(mediaItems).build()
        val composition = Composition.Builder(sequence).build()

        // Keep large intermediate exports outside the cache (which Android may purge).
        val tempDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.cacheDir
        if (!tempDirectory.exists() && !tempDirectory.mkdirs()) error("Could not create temporary export storage.")
        val temp = File(tempDirectory, "MergerOnly_${System.currentTimeMillis()}.mp4")
        progress(3, "Preparing a common MP4 format…")

        try {
            exportOnMainThread(composition, temp, progress)
            require(temp.exists() && temp.isFile && temp.length() > 0L) {
                "Conversion finished without a valid output file."
            }

            withContext(Dispatchers.IO) {
                progress(97, "Saving merged MP4…")
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "MergerOnly_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Merger Only")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                val output = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Android could not create the output. Check free storage space.")
                var saved = false
                try {
                    context.contentResolver.openOutputStream(output, "w").use { stream ->
                        requireNotNull(stream) { "Could not open the output file." }
                        temp.inputStream().use { input -> input.copyTo(stream, 1024 * 1024) }
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        context.contentResolver.update(
                            output,
                            ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                            null,
                            null
                        )
                    }
                    saved = true
                } finally {
                    if (!saved) {
                        try { context.contentResolver.delete(output, null, null) } catch (_: Exception) { }
                    }
                }
            }
            progress(100, "Merge complete. Saved in Movies/Merger Only")
        } finally {
            try { temp.delete() } catch (_: Exception) { }
        }
    }

    private suspend fun exportOnMainThread(
        composition: Composition,
        output: File,
        progress: (Int, String) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        var progressJob: Job? = null

        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                mainHandler.post {
                    if (!continuation.isActive) return@post

                    try {
                        lateinit var transformer: Transformer
                        transformer = Transformer.Builder(context)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException
                                ) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            IllegalStateException(
                                                "Could not convert one of the selected videos: " +
                                                    (exportException.message ?: "unsupported or damaged media")
                                            )
                                        )
                                    }
                                }
                            })
                            .build()

                        // Media3 Transformer is main-looper confined by default. Keep start and
                        // progress polling on that looper while the service coroutine remains non-blocking.
                        progressJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                            val holder = ProgressHolder()
                            while (continuation.isActive) {
                                try {
                                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                        progress(
                                            (5 + holder.progress * 90 / 100).coerceIn(5, 95),
                                            "Converting clips to MP4 • ${holder.progress}%"
                                        )
                                    }
                                } catch (_: Exception) { }
                                delay(500)
                            }
                        }
                        continuation.invokeOnCancellation {
                            mainHandler.post {
                                progressJob?.cancel()
                                try { transformer.cancel() } catch (_: Exception) { }
                            }
                        }
                        transformer.start(composition, output.absolutePath)
                    } catch (t: Throwable) {
                        progressJob?.cancel()
                        if (continuation.isActive) continuation.resumeWithException(t)
                    }
                }
            }
        } finally {
            progressJob?.cancel()
        }
    }
}
