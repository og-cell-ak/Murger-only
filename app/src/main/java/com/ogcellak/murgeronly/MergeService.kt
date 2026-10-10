package com.ogcellak.murgeronly

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

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
            startForeground(NOTIFICATION_ID, notice("Checking MP4 files…", 0))
            val uris = intent.getStringArrayListExtra("uris") ?: arrayListOf()
            getSharedPreferences("merge_state", MODE_PRIVATE).edit()
                .putBoolean("running", true)
                .putInt("progress", 0)
                .putLong("started", System.currentTimeMillis())
                .putString("message", "Checking MP4 files…")
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

private class Merger(private val context: Context) {
    suspend fun run(items: ArrayList<String>, progress: (Int, String) -> Unit) {
        require(items.size >= 2) { "Select at least two MP4 videos." }
        val uris = items.map { Uri.parse(it) }
        val tempDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.cacheDir
        if (!tempDirectory.exists() && !tempDirectory.mkdirs()) error("Could not create temporary export storage.")
        val temp = File(tempDirectory, "MergerOnly_" + System.currentTimeMillis() + ".mp4")

        try {
            withContext(Dispatchers.IO) {
                mergeWithoutReencoding(uris, temp, progress)
            }
            require(temp.exists() && temp.isFile && temp.length() > 0L) {
                "Merge finished without a valid output file."
            }
            withContext(Dispatchers.IO) {
                progress(97, "Saving merged MP4 without re-encoding…")
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "MergerOnly_" + System.currentTimeMillis() + ".mp4")
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
            progress(100, "Merge complete. Original audio/video streams preserved • Movies/Merger Only")
        } finally {
            try { temp.delete() } catch (_: Exception) { }
        }
    }

    private fun mergeWithoutReencoding(
        uris: List<Uri>,
        output: File,
        progress: (Int, String) -> Unit
    ) {
        val extractors = ArrayList<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerStopped = false
        try {
            val tracksPerClip = ArrayList<List<Int>>()
            val formatsPerClip = ArrayList<List<MediaFormat>>()

            uris.forEachIndexed { index, uri ->
                progress((index * 8 / uris.size).coerceAtMost(12), "Checking MP4 " + (index + 1) + "/" + uris.size + "…")
                require(hasMp4Signature(uri)) {
                    "File " + (index + 1) + " is not a valid MP4 container. Select .mp4 files only."
                }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, uri, null)
                } catch (t: Throwable) {
                    extractor.release()
                    error("Android could not read MP4 " + (index + 1) + ". The file may be damaged, protected, or use an unsupported codec.")
                }
                extractors.add(extractor)

                val selected = ArrayList<Int>()
                for (trackIndex in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(trackIndex)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) selected.add(trackIndex)
                }
                selected.sortWith(compareBy<Int> {
                    val mime = extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) 0 else 1
                }.thenBy { it })

                require(selected.any {
                    (extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")
                }) { "MP4 " + (index + 1) + " has no readable video track." }
                selected.forEach { extractor.selectTrack(it) }
                tracksPerClip.add(selected)
                formatsPerClip.add(selected.map { extractor.getTrackFormat(it) })
            }

            val referenceTracks = tracksPerClip.first()
            val referenceFormats = formatsPerClip.first()
            for (clip in 1 until uris.size) {
                require(tracksPerClip[clip].size == referenceTracks.size) {
                    "MP4 " + (clip + 1) + " has a different number of audio/video tracks. To prevent quality loss, this app won't re-encode it."
                }
                for (i in referenceFormats.indices) {
                    require(formatsMatch(referenceFormats[i], formatsPerClip[clip][i])) {
                        val mime = referenceFormats[i].getString(MediaFormat.KEY_MIME) ?: "media"
                        "MP4 " + (clip + 1) + " has a different " + mime + " format (codec, dimensions, or audio settings). Lossless merging requires matching track formats; no re-encoding was done."
                    }
                }
            }

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrackForSelected = referenceFormats.mapIndexed { index, format ->
                if (index == 0) {
                    val rotation = runCatching { format.getInteger("rotation-degrees") }.getOrNull() ?: 0
                    if (rotation in listOf(0, 90, 180, 270)) muxer!!.setOrientationHint(rotation)
                }
                muxer!!.addTrack(format)
            }
            muxer!!.start()
            muxerStarted = true

            var timelineOffsetUs = 0L
            extractors.forEachIndexed { clipIndex, extractor ->
                progress((12 + clipIndex * 80 / uris.size).coerceAtMost(92),
                    "Joining MP4 " + (clipIndex + 1) + "/" + uris.size + " without re-encoding…")
                val selectedTracks = tracksPerClip[clipIndex]
                val formats = formatsPerClip[clipIndex]
                val sourceToOutput = HashMap<Int, Int>()
                selectedTracks.forEachIndexed { selectedIndex, sourceIndex ->
                    sourceToOutput[sourceIndex] = outputTrackForSelected[selectedIndex]
                }

                val buffers = HashMap<Int, ByteBuffer>()
                selectedTracks.forEachIndexed { selectedIndex, sourceIndex ->
                    val format = formats[selectedIndex]
                    val hint = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(0) }.getOrDefault(0)
                    val capacity = maxOf(4 * 1024 * 1024, hint).coerceAtMost(64 * 1024 * 1024)
                    buffers[sourceIndex] = ByteBuffer.allocateDirect(capacity)
                }

                var maxPresentationTimeUs = 0L
                while (true) {
                    val sourceTrack = extractor.sampleTrackIndex
                    if (sourceTrack < 0) break
                    val buffer = buffers[sourceTrack]
                    if (buffer != null) {
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break
                        require(sampleSize <= buffer.capacity()) {
                            "A sample in MP4 " + (clipIndex + 1) + " is too large for safe lossless copying."
                        }
                        val sourceTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                        maxPresentationTimeUs = maxOf(maxPresentationTimeUs, sourceTimeUs)
                        val info = MediaCodec.BufferInfo().apply {
                            set(0, sampleSize, sourceTimeUs + timelineOffsetUs, extractor.sampleFlags)
                        }
                        buffer.position(0)
                        buffer.limit(sampleSize)
                        muxer!!.writeSampleData(sourceToOutput[sourceTrack]!!, buffer, info)
                    }
                    if (!extractor.advance()) break
                }

                val declaredDurationUs = formats.mapNotNull { format ->
                    runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrNull()
                }.maxOrNull() ?: 0L
                timelineOffsetUs += maxOf(declaredDurationUs, maxPresentationTimeUs + 1L)
            }
            muxer!!.stop()
            muxerStopped = true
        } finally {
            if (muxerStarted && !muxerStopped) {
                try { muxer?.stop() } catch (_: Exception) { }
            }
            try { muxer?.release() } catch (_: Exception) { }
            extractors.forEach { try { it.release() } catch (_: Exception) { } }
        }
    }

    private fun hasMp4Signature(uri: Uri): Boolean {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open the selected file." }
            val header = ByteArray(12)
            var count = 0
            while (count < header.size) {
                val read = input.read(header, count, header.size - count)
                if (read <= 0) break
                count += read
            }
            return count >= 8 && String(header, 4, 4, StandardCharsets.US_ASCII) == "ftyp"
        }
    }

    private fun formatsMatch(a: MediaFormat, b: MediaFormat): Boolean {
        if (a.getString(MediaFormat.KEY_MIME) != b.getString(MediaFormat.KEY_MIME)) return false
        val importantKeys = listOf(
            MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT,
            MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT,
            MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL,
            MediaFormat.KEY_AAC_PROFILE, "color-standard", "color-transfer",
            "color-range", "rotation-degrees", "bit-depth", "pcm-encoding"
        )
        for (key in importantKeys) {
            val valueA = runCatching { a.getInteger(key) }.getOrNull()
            val valueB = runCatching { b.getInteger(key) }.getOrNull()
            if (valueA != valueB) return false
        }
        for (key in listOf("csd-0", "csd-1", "csd-2")) {
            val left = bytesForFormat(a, key)
            val right = bytesForFormat(b, key)
            if (left == null && right == null) continue
            if (left == null || right == null || !left.contentEquals(right)) return false
        }
        return true
    }

    private fun bytesForFormat(format: MediaFormat, key: String): ByteArray? {
        val buffer = runCatching { format.getByteBuffer(key) }.getOrNull() ?: return null
        val copy = buffer.duplicate()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }
}
