package com.ogcellak.murgeronly

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.util.ArrayList
import kotlin.math.max

class MainActivity : Activity() {
    private val pickerCode = 42
    private val names = ArrayList<String>()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var merge: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var percent: TextView
    private lateinit var eta: TextView
    private var receiverRegistered = false

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MergeService.PROGRESS) return
            showProgress(intent.getIntExtra("progress", 0), intent.getStringExtra("message") ?: "Merging…")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui()
        val prefs = getSharedPreferences("merge_state", MODE_PRIVATE)
        if (prefs.getBoolean("running", false)) showProgress(prefs.getInt("progress", 0), prefs.getString("message", "Merge in progress…") ?: "Merge in progress…")
        else if (prefs.getInt("progress", 0) == 100) showProgress(100, prefs.getString("message", "Merge complete") ?: "Merge complete")
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        val f = IntentFilter(MergeService.PROGRESS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(progressReceiver, f, RECEIVER_NOT_EXPORTED)
        else registerReceiver(progressReceiver, f)
        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(progressReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun ui() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(Color.rgb(10, 16, 32))
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.rgb(23, 35, 61), 20)
        }
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_murger_launcher) }
        hero.addView(logo, LinearLayout.LayoutParams(dp(64), dp(64)))
        val headings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        headings.addView(TextView(this).apply {
            text = "MERGER ONLY"; textSize = 23f; setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        headings.addView(TextView(this).apply {
            text = "Join videos. Keep the original streams."
            textSize = 13f; setTextColor(Color.rgb(180, 197, 224)); setPadding(0, dp(4), 0, 0)
        })
        hero.addView(headings, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(hero)

        root.addView(TextView(this).apply {
            text = "MIXED-FORMAT MP4 EXPORT"
            textSize = 12f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.rgb(100, 211, 190)); setPadding(dp(2), dp(20), 0, dp(5))
        })
        root.addView(TextView(this).apply {
            text = "Different supported video formats are converted to a common H.264/AAC MP4 before joining. Conversion can change quality slightly; keep enough free storage for the export."
            textSize = 14f; setTextColor(Color.rgb(211, 220, 237)); setPadding(dp(2), 0, dp(2), dp(14))
        })

        val add = Button(this).apply { text = "＋  Add videos" }
        val clear = Button(this).apply { text = "Clear selection" }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(add, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
        buttons.addView(clear, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        root.addView(buttons)

        status = TextView(this).apply {
            text = "No videos selected."; textSize = 14f; setTextColor(Color.WHITE)
            setPadding(dp(2), dp(14), 0, dp(8))
        }
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { isFillViewport = false; addView(list) },
            LinearLayout.LayoutParams(-1, 0, 1f))

        val progressCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.rgb(23, 35, 61), 18)
        }
        val progressHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        percent = TextView(this).apply {
            text = "0%"; textSize = 24f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        eta = TextView(this).apply {
            text = "Ready when you are"; textSize = 12f; setTextColor(Color.rgb(180, 197, 224))
            gravity = Gravity.END
        }
        progressHead.addView(percent, LinearLayout.LayoutParams(0, -2, 1f))
        progressHead.addView(eta, LinearLayout.LayoutParams(0, -2, 1f))
        progressCard.addView(progressHead)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(65, 214, 182))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(51, 66, 94))
        }
        progressCard.addView(progressBar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(10) })
        root.addView(progressCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        merge = Button(this).apply { text = "MERGE VIDEOS"; isEnabled = false }
        root.addView(merge, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(12) })
        add.setOnClickListener { pick() }
        clear.setOnClickListener { names.clear(); refresh() }
        merge.setOnClickListener {
            if (names.size < 2) return@setOnClickListener
            val intent = Intent(this, MergeService::class.java).apply {
                action = MergeService.START
                putStringArrayListExtra("uris", ArrayList(names))
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            showProgress(0, "Preparing files…")
            merge.isEnabled = false
        }
        setContentView(root)
        refresh()
    }

    private fun pick() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, pickerCode)
    }

    @Deprecated("Deprecated by Android; retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickerCode || resultCode != RESULT_OK || data == null) return
        val selected = ArrayList<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) selected.add(clip.getItemAt(i).uri) }
            ?: data.data?.let { selected.add(it) }
        for (uri in selected) if (!names.contains(uri.toString())) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            names.add(uri.toString())
        }
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        names.forEachIndexed { index, uri ->
            list.addView(TextView(this).apply {
                text = "${index + 1}. ${Uri.parse(uri).lastPathSegment?.substringAfterLast('/')?.takeLast(90) ?: "Video file"}"
                textSize = 13f; setTextColor(Color.rgb(211, 220, 237)); setPadding(dp(8), dp(8), dp(8), dp(8))
            })
        }
        status.text = if (names.isEmpty()) "No videos selected." else
            "${names.size} video(s) selected • merge order follows this list"
        merge.isEnabled = names.size >= 2
    }

    private fun showProgress(value: Int, message: String) {
        val p = value.coerceIn(0, 100)
        progressBar.progress = p
        percent.text = "${p}%"
        status.text = message
        val prefs = getSharedPreferences("merge_state", MODE_PRIVATE)
        val start = prefs.getLong("started", 0L)
        val elapsed = max(1L, (System.currentTimeMillis() - start) / 1000L)
        eta.text = when {
            p >= 100 -> "Finished"
            message.startsWith("Merge failed:") -> "Ready to retry"
            p <= 0 || start <= 0L -> "Estimating time…"
            else -> "About ${formatTime((elapsed.toDouble() * (100 - p) / max(1, p)).toLong())} left"
        }
        if (p >= 100 || message.startsWith("Merge failed:")) {
            merge.isEnabled = names.size >= 2
        }
    }

    private fun formatTime(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
}
