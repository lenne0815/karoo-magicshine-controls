package com.lenne0815.karoosramsniffer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.RequestAnt
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), RawAntSniffer.Listener {
    companion object {
        private const val TAG = "SramAntSniffer"
        private const val MAX_VISIBLE_LINES = 180
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val karooSystem by lazy { KarooSystemService(this) }
    private val visibleLines = ArrayDeque<String>()

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var recorder: FrameRecorder
    private lateinit var sniffer: RawAntSniffer

    private var extensionBound = false

    private val extensionConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            appendLog("Karoo extension service bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            appendLog("Karoo extension service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = FrameRecorder(this)
        sniffer = RawAntSniffer(this, this)
        setContentView(buildContentView())

        appendLog("Recorder: ${recorder.file.absolutePath}")
        bindExtensionService()
        connectKarooSystem()
        sniffer.start()
    }

    override fun onDestroy() {
        sniffer.stop()
        runCatching { karooSystem.disconnect() }
        if (extensionBound) {
            runCatching { unbindService(extensionConnection) }
            extensionBound = false
        }
        super.onDestroy()
    }

    override fun onSnifferLine(line: String) {
        appendLog(line)
    }

    private fun buildContentView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundColor(getColor(R.color.sniffer_background))
            layoutParams = LinearLayout.LayoutParams(match(), match())
        }

        val title = TextView(this).apply {
            text = "SRAM ANT Sniffer"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.sniffer_text))
        }
        root.addView(title, LinearLayout.LayoutParams(match(), wrap()))

        statusView = TextView(this).apply {
            text = "Raw ANT+ frames on RF 57. Use markers before button presses."
            textSize = 14f
            setTextColor(getColor(R.color.sniffer_muted))
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(statusView, LinearLayout.LayoutParams(match(), wrap()))

        root.addView(buildButtonRow("MARK SHIFT UP", "MARK SHIFT DOWN", "MARK BOTH") { label ->
            mark(label)
        })
        root.addView(buildButtonRow("START", "STOP", "CLEAR") { label ->
            when (label) {
                "START" -> {
                    appendLog("Manual start")
                    sniffer.start()
                }
                "STOP" -> sniffer.stop()
                "CLEAR" -> {
                    visibleLines.clear()
                    logView.text = ""
                    appendLog("Visible log cleared; recorder file continues")
                }
            }
        })

        scrollView = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.sniffer_panel))
            isFillViewport = true
        }
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.sniffer_text))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        scrollView.addView(logView, ViewGroup.LayoutParams(match(), wrap()))
        root.addView(scrollView, LinearLayout.LayoutParams(match(), 0, 1f))

        return root
    }

    private fun buildButtonRow(
        first: String,
        second: String,
        third: String,
        onClick: (String) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        listOf(first, second, third).forEach { label ->
            row.addView(Button(this).apply {
                text = label
                textSize = 12f
                setOnClickListener { onClick(label) }
            }, LinearLayout.LayoutParams(0, wrap(), 1f).apply {
                marginEnd = dp(6)
            })
        }
        return row
    }

    private fun bindExtensionService() {
        extensionBound = bindService(
            Intent(this, SramSnifferKarooExtension::class.java),
            extensionConnection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
        appendLog("Extension bind requested: bound=$extensionBound")
    }

    private fun connectKarooSystem() {
        runCatching {
            karooSystem.connect { connected ->
                mainHandler.post {
                    appendLog("KarooSystem connected=$connected")
                    if (connected) {
                        requestAnt("activity-connected")
                    }
                }
            }
        }.onFailure { throwable ->
            appendLog("Unable to connect KarooSystem: ${throwable.message}")
        }
    }

    private fun requestAnt(reason: String) {
        runCatching {
            karooSystem.dispatch(RequestAnt(SramSnifferKarooExtension.EXTENSION_ID))
            appendLog("Requested Karoo ANT access ($reason)")
        }.onFailure { throwable ->
            appendLog("Unable to request Karoo ANT access ($reason): ${throwable.message}")
        }
    }

    private fun mark(label: String) {
        appendLog("========== $label ==========")
    }

    private fun appendLog(line: String) {
        val stamped = "${timestamp()} $line"
        Log.i(TAG, stamped)
        recorder.append(stamped)
        visibleLines.addLast(stamped)
        while (visibleLines.size > MAX_VISIBLE_LINES) {
            visibleLines.removeFirst()
        }
        logView.text = visibleLines.joinToString("\n")
        statusView.text = "Writing ${recorder.file.name}"
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun match(): Int = ViewGroup.LayoutParams.MATCH_PARENT

    private fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT
}
