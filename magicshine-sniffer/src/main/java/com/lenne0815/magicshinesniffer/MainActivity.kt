package com.lenne0815.magicshinesniffer

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.RequestBluetooth
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    companion object {
        private const val TAG = "MagicshineBleSniffer"
        private const val MAX_VISIBLE_LINES = 160
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 41
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val karooSystem by lazy { KarooSystemService(this) }
    private val visibleLines = ArrayDeque<String>()

    private lateinit var recorder: FrameRecorder
    private lateinit var sniffer: MagicshineBleSniffer
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        recorder = FrameRecorder(this)
        sniffer = MagicshineBleSniffer(this, ::appendLog)
        setContentView(buildContentView())
        appendLog("Recorder: ${recorder.file.absolutePath}")
        connectKarooSystem()
        requestRuntimePermissions()
    }

    override fun onDestroy() {
        sniffer.close()
        runCatching { karooSystem.disconnect() }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            appendLog("Runtime Bluetooth permissions granted=${results.all { it == PackageManager.PERMISSION_GRANTED }}")
        }
    }

    private fun buildContentView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(getColor(R.color.sniffer_background))
        }
        root.addView(TextView(this).apply {
            text = "Magicshine BLE Sniffer"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.sniffer_text))
        }, LinearLayout.LayoutParams(match(), wrap()))
        statusView = TextView(this).apply {
            text = "Connect alone; the control app and official app must be disconnected."
            textSize = 13f
            setTextColor(getColor(R.color.sniffer_muted))
            setPadding(0, dp(2), 0, dp(6))
        }
        root.addView(statusView, LinearLayout.LayoutParams(match(), wrap()))

        root.addView(buttonRow("CONNECT", "DISCONNECT", "MARK CURRENT"))
        root.addView(buttonRow("A1 TEMP", "A4 CANDIDATE", "SUPPORT SWEEP"))
        root.addView(buttonRow("AB", "AC", "AD"))
        root.addView(buttonRow("POLL A4", "STOP POLL", "OFFICIAL BURST"))
        root.addView(buttonRow("A5 PROFILES", "A9 RUNTIME", "GATT READS"))
        root.addView(buttonRow("DRAIN 100%", "STOP POLL", "MARK CURRENT"))

        scrollView = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.sniffer_panel))
            isFillViewport = true
        }
        logView = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.sniffer_text))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scrollView.addView(logView, ViewGroup.LayoutParams(match(), wrap()))
        root.addView(scrollView, LinearLayout.LayoutParams(match(), 0, 1f))
        return root
    }

    private fun buttonRow(vararg labels: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        labels.forEach { label ->
            addView(Button(this@MainActivity).apply {
                text = label
                textSize = 10f
                setOnClickListener { handleButton(label) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        }
    }

    private fun handleButton(label: String) {
        when (label) {
            "CONNECT" -> sniffer.connect()
            "DISCONNECT" -> sniffer.disconnect()
            "MARK CURRENT" -> appendLog("========== MARK CURRENT PHYSICAL BATTERY ==========")
            "A1 TEMP" -> sniffer.send(label, MagicshineBleSniffer.QUERY_TEMPERATURE)
            "A4 CANDIDATE" -> sniffer.send(label, MagicshineBleSniffer.QUERY_BATTERY)
            "SUPPORT SWEEP" -> sniffer.runSupportSweep()
            "AB" -> sniffer.send(label, MagicshineBleSniffer.QUERY_AB)
            "AC" -> sniffer.send(label, MagicshineBleSniffer.QUERY_AC)
            "AD" -> sniffer.send(label, MagicshineBleSniffer.QUERY_AD)
            "POLL A4" -> sniffer.startBatteryPolling()
            "STOP POLL" -> sniffer.stopPolling()
            "OFFICIAL BURST" -> sniffer.runOfficialSequence()
            "A5 PROFILES" -> sniffer.send(label, MagicshineBleSniffer.QUERY_PROFILES)
            "A9 RUNTIME" -> sniffer.send(label, MagicshineBleSniffer.QUERY_ENDURANCE)
            "GATT READS" -> sniffer.runGattSurvey()
            "DRAIN 100%" -> sniffer.startFullPowerDischarge()
        }
    }

    private fun connectKarooSystem() {
        runCatching {
            karooSystem.connect { connected ->
                appendLog("KarooSystem connected=$connected")
                if (connected) {
                    runCatching {
                        karooSystem.dispatch(RequestBluetooth(MagicshineSnifferKarooExtension.EXTENSION_ID))
                    }.onFailure { appendLog("ERROR RequestBluetooth: ${it.message}") }
                }
            }
        }.onFailure { appendLog("ERROR KarooSystem: ${it.message}") }
    }

    private fun requestRuntimePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
    }

    private fun appendLog(message: String) {
        val stamped = "${timestamp()} $message"
        Log.i(TAG, stamped)
        recorder.append(stamped)
        mainHandler.post {
            visibleLines.addLast(stamped)
            while (visibleLines.size > MAX_VISIBLE_LINES) visibleLines.removeFirst()
            logView.text = visibleLines.joinToString("\n")
            statusView.text = "Writing ${recorder.file.name}"
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun timestamp(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun match(): Int = ViewGroup.LayoutParams.MATCH_PARENT

    private fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT
}
