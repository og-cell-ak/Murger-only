package com.ogcellak.murgeronly

import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ProgressHolder
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import kotlin.math.max

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
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Video merging", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == START) {
            startForeground(NOTIFICATION_ID, notice("Checking videos…", 0))
            val uris = intent.getStringArrayListExtra("uris") ?: arrayListOf()
            getSharedPreferences("merge_state", MODE_PRIVATE).edit()
                .putBoolean("running", true).putInt("progress", 0)
                .putLong("started", System.currentTimeMillis()).putString("message", "Checking videos…").apply()
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

    private fun notice(message: String, progress: Int) = NotificationCompat.Builder(this, CHANNEL)
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
            .putBoolean("running", running).putInt("progress", p)
            .putString("message", message).apply()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notice(message, p))
        sendBroadcast(Intent(PROGRESS).setPackage(packageName).putExtra("progress", p).putExtra("message", message))
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?) = null
}

private class Merger(private val context: Context) {
    private data class Clip(val uri: String, val videoTrack: Int, val audioTrack: Int,
        val videoFormat: MediaFormat, val audioFormat: MediaFormat?, val videoDuration: Long, val audioDuration: Long)

    fun run(items: ArrayList<String>, progress: (Int, String) -> Unit) {
        require(items.size >= 2) { "Select at least two videos." }
        progress(1, "Checking formats before creating output…")
        val clips = ArrayList<Clip>()
        for ((index, item) in items.withIndex()) {
            val extractor = open(item)
            try {
                val video = find(extractor, "video/")
                val audio = find(extractor, "audio/")
                require(video >= 0) { "Video ${index + 1} has no readable video track." }
                val vf = extractor.getTrackFormat(video)
                val af = if (audio >= 0) extractor.getTrackFormat(audio) else null
                if (clips.isNotEmpty()) {
                    require(compatible(clips[0].videoFormat, vf)) {
                        "Video ${index + 1} is not compatible for lossless merging. " +
                            "First: ${describe(clips[0].videoFormat)}; this clip: ${describe(vf)}. " +
                            "No video was changed. Clips with different codecs or resolutions need conversion, which would re-encode."
                    }
                    val firstAudio = clips[0].audioFormat
                    require((firstAudio == null && af == null) || (firstAudio != null && af != null && compatible(firstAudio, af))) {
                        "Video ${index + 1} has incompatible audio tracks. " +
                            "First: ${firstAudio?.let { describe(it) } ?: "no audio"}; this clip: ${af?.let { describe(it) } ?: "no audio"}. No output was created."
                    }
                }
                clips.add(Clip(item, video, audio, vf, af, duration(extractor, video),
                    if (audio >= 0) duration(extractor, audio) else 0L))
            } finally { extractor.release() }
        }
        require(clips.sumOf { it.videoDuration } > 0L) { "Could not read video durations. Try standard MP4 files with timestamps." }
        mergeValidated(clips, progress)
    }

    private fun mergeValidated(clips: List<Clip>, progress: (Int, String) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "MergerOnly_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Merger Only")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val output = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Android could not create the output file. Check free storage space.")
        var pfd: ParcelFileDescriptor? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var finished = false
        try {
            pfd = context.contentResolver.openFileDescriptor(output, "w")
                ?: error("Could not open output file. Check free storage space and permissions.")
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideo = muxer.addTrack(clips[0].videoFormat)
            val outAudio = clips[0].audioFormat?.let { muxer.addTrack(it) } ?: -1
            muxer.start()
            muxerStarted = true

            val totalVideo = clips.sumOf { it.videoDuration }.coerceAtLeast(1L)
            val totalAudio = clips.sumOf { it.audioDuration }.coerceAtLeast(1L)
            var completedVideo = 0L
            var completedAudio = 0L
            var videoOffset = 0L
            var audioOffset = 0L
            for ((index, clip) in clips.withIndex()) {
                val extractor = open(clip.uri)
                try {
                    val label = "Copying video ${index + 1}/${clips.size}"
                    copyTrack(extractor, clip.videoTrack, outVideo, muxer, videoOffset, 8 * 1024 * 1024) { sampleTime ->
                        val local = sampleTime.coerceIn(0L, clip.videoDuration.coerceAtLeast(1L))
                        val overall = ((completedVideo + local).toDouble() / totalVideo * 85.0).toInt().coerceIn(1, 85)
                        progress(overall, "$label • original quality")
                    }
                    if (outAudio >= 0 && clip.audioTrack >= 0) {
                        val audioExtractor = open(clip.uri)
                        try {
                            copyTrack(audioExtractor, clip.audioTrack, outAudio, muxer, audioOffset, 2 * 1024 * 1024) { sampleTime ->
                                val local = sampleTime.coerceIn(0L, clip.audioDuration.coerceAtLeast(1L))
                                val overall = (85.0 + (completedAudio + local).toDouble() / totalAudio * 14.0).toInt().coerceIn(85, 99)
                                progress(overall, "Copying audio ${index + 1}/${clips.size}")
                            }
                        } finally { audioExtractor.release() }
                        audioOffset += clip.audioDuration
                        completedAudio += clip.audioDuration
                    }
                    videoOffset += clip.videoDuration
                    completedVideo += clip.videoDuration
                } finally { extractor.release() }
            }
            progress(99, "Finalizing MP4…")
            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null
            pfd.close()
            pfd = null
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(output,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            finished = true
            progress(100, "Merge complete. Saved in Movies/Merger Only")
        } finally {
            if (muxerStarted) try { muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            try { pfd?.close() } catch (_: Exception) { }
            if (!finished) try { context.contentResolver.delete(output, null, null) } catch (_: Exception) { }
        }
    }

    private fun open(uri: String) = MediaExtractor().also { it.setDataSource(context, Uri.parse(uri), null) }
    private fun find(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
        }
        return -1
    }
    private fun duration(extractor: MediaExtractor, track: Int): Long {
        val format = extractor.getTrackFormat(track)
        return if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L) else 0L
    }
    private fun describe(format: MediaFormat): String {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown codec"
        val size = if (mime.startsWith("video/")) {
            "${format.integerOrNull(MediaFormat.KEY_WIDTH) ?: "?"}x${format.integerOrNull(MediaFormat.KEY_HEIGHT) ?: "?"}"
        } else {
            "${format.integerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: "?"} Hz / ${format.integerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: "?"} channels"
        }
        return "$mime ($size)"
    }
    private fun MediaFormat.integerOrNull(key: String): Int? =
        if (containsKey(key)) try { getInteger(key) } catch (_: Exception) { null } else null

    private fun compatible(a: MediaFormat, b: MediaFormat): Boolean {
        if (a.getString(MediaFormat.KEY_MIME) != b.getString(MediaFormat.KEY_MIME)) return false
        // Container extensions may differ (MP4, MOV, MKV, WebM, etc.). For lossless
        // stream-copy, require the same codec and core dimensions/audio layout, but allow
        // variable frame rates and codec profile/level metadata to differ between clips.
        val keys = listOf(MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_SAMPLE_RATE,
            MediaFormat.KEY_CHANNEL_COUNT)
        return keys.all { key ->
            !a.containsKey(key) || !b.containsKey(key) || try { a.getInteger(key) == b.getInteger(key) } catch (_: Exception) { true }
        }
    }
    private fun copyTrack(extractor: MediaExtractor, track: Int, destinationTrack: Int, muxer: MediaMuxer,
        offsetUs: Long, initialBufferSize: Int, onSample: (Long) -> Unit) {
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val suggested = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            try { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(initialBufferSize) } catch (_: Exception) { initialBufferSize }
        } else initialBufferSize
        val buffer = ByteBuffer.allocateDirect(max(initialBufferSize, suggested))
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                val sampleTime = extractor.sampleTime.coerceAtLeast(0L)
                info.presentationTimeUs = sampleTime + offsetUs
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(destinationTrack, buffer, info)
                onSample(sampleTime)
                extractor.advance()
            }
        } finally { extractor.unselectTrack(track) }
    }
}
