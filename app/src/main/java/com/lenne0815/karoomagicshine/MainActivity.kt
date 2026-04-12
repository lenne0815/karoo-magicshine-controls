package com.lenne0815.karoomagicshine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lenne0815.karoomagicshine.extension.LampCandidate
import com.lenne0815.karoomagicshine.extension.MagicshineBleController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private enum class OutputTarget {
        LOW,
        HIGH,
        OFF,
    }

    companion object {
        private const val PREFS_NAME = "magicshine_prefs"
        private const val PREF_SELECTED_LAMP_ADDRESS = "selected_lamp_address"
        private const val PREF_SELECTED_LAMP_NAME = "selected_lamp_name"
    }

    private lateinit var controller: MagicshineBleController
    private lateinit var batteryView: TextView
    private lateinit var temperatureView: TextView
    private lateinit var changeLampButton: View
    private lateinit var changeLampLabel: TextView
    private lateinit var chooserGate: LinearLayout
    private lateinit var controlPanel: LinearLayout
    private lateinit var chooserHintView: TextView
    private lateinit var lampCandidatesLayout: LinearLayout
    private lateinit var connectButton: View
    private lateinit var connectLabelView: TextView
    private lateinit var connectStateView: TextView
    private lateinit var module1Button: View
    private lateinit var module2Button: View
    private lateinit var offButton: View
    private lateinit var module1Label: TextView
    private lateinit var module2Label: TextView
    private lateinit var level25Button: View
    private lateinit var level50Button: View
    private lateinit var level75Button: View
    private lateinit var level100Button: View
    private lateinit var sosButton: View
    private lateinit var blitzButton: View
    private lateinit var disconnectButton: View
    private lateinit var prefs: android.content.SharedPreferences
    private var currentConnectionStatus: String = "disconnected"
    private var currentBatteryStatus: String = "?"
    private var currentTemperatureStatus: String = "?"
    private var currentDisplayStatus: String = "idle"
    private var selectedModule: MagicshineModule = MagicshineModule.MODULE_1
    private var selectedOutputTarget: OutputTarget = OutputTarget.LOW
    private var selectedLevelPercent: Int? = null
    private var currentSelectedLampAddress: String? = null
    private var currentSelectedLampName: String? = null
    private var lastRenderedCandidateSignature: String = ""

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            restoreSelectedLamp()
            controller.startDiscovery()
        } else {
            currentDisplayStatus = "permissions"
            updateConnectButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        changeLampButton = findViewById(R.id.btnChangeLamp)
        changeLampLabel = findViewById(R.id.txtChangeLampLabel)
        chooserGate = findViewById(R.id.lampChooserGate)
        controlPanel = findViewById(R.id.layoutControlPanel)
        chooserHintView = findViewById(R.id.txtChooserHint)
        lampCandidatesLayout = findViewById(R.id.layoutLampCandidates)
        batteryView = findViewById(R.id.txtBattery)
        temperatureView = findViewById(R.id.txtTemperature)
        connectButton = findViewById(R.id.btnConnect)
        connectLabelView = findViewById(R.id.txtConnectLabel)
        connectStateView = findViewById(R.id.txtConnectState)
        module1Button = findViewById(R.id.btnModule1)
        module2Button = findViewById(R.id.btnModule2)
        offButton = findViewById(R.id.btnOff)
        module1Label = findViewById(R.id.txtModule1)
        module2Label = findViewById(R.id.txtModule2)
        level25Button = findViewById(R.id.btnLevel25)
        level50Button = findViewById(R.id.btnLevel50)
        level75Button = findViewById(R.id.btnLevel75)
        level100Button = findViewById(R.id.btnLevel100)
        sosButton = findViewById(R.id.btnSos)
        blitzButton = findViewById(R.id.btnBlitz)
        disconnectButton = findViewById(R.id.btnDisconnect)
        controller = MagicshineBleController.getShared(
            this,
            onStatus = { s ->
                runOnUiThread {
                    currentDisplayStatus = displayStatus(s)
                    updateConnectButton()
                }
            },
            onConnectionStatus = { s ->
                runOnUiThread {
                    currentConnectionStatus = s
                    updateConnectButton()
                }
            },
            onBatteryStatus = { s ->
                runOnUiThread {
                    currentBatteryStatus = s
                    batteryView.text = s
                    updateConnectButton()
                }
            },
            onTemperatureStatus = { s ->
                runOnUiThread {
                    currentTemperatureStatus = s
                    temperatureView.text = s
                }
            },
        )
        restoreSelectedLamp()
        if (hasPermissions()) {
            controller.startDiscovery()
        } else {
            ensurePermissions()
        }
        lifecycleScope.launch {
            while (true) {
                refreshUiFromController()
                delay(500)
            }
        }

        connectButton.setOnClickListener {
            connectIfPermitted()
        }
        changeLampButton.setOnClickListener {
            controller.stopRepeatingCommand()
            controller.disconnect()
            saveSelectedLamp(null)
            controller.setPreferredAddress(null)
            controller.startDiscovery(forceRestart = true)
            refreshLampSelectionUi()
        }
        module1Button.setOnClickListener {
            controller.stopRepeatingCommand()
            selectedOutputTarget = OutputTarget.LOW
            selectedModule = MagicshineModule.MODULE_1
            updateOutputControls()
            updateBrightnessControls()
            resendSelectedLevelForCurrentModule()
        }
        module2Button.setOnClickListener {
            controller.stopRepeatingCommand()
            selectedOutputTarget = OutputTarget.HIGH
            selectedModule = MagicshineModule.MODULE_2
            updateOutputControls()
            updateBrightnessControls()
            resendSelectedLevelForCurrentModule()
        }
        offButton.setOnClickListener {
            controller.stopRepeatingCommand()
            selectedOutputTarget = OutputTarget.OFF
            selectedLevelPercent = null
            updateOutputControls()
            updateBrightnessControls()
            sendIfPermitted(MagicshineProtocol.buildPresetFrame(MagicshineModule.MODULE_1, 0))
            sendIfPermitted(MagicshineProtocol.buildPresetFrame(MagicshineModule.MODULE_2, 0))
        }
        updateConnectButton()
        updateOutputControls()
        updateBrightnessControls()
        refreshLampSelectionUi()
        level25Button.setOnClickListener { sendLevel(25) }
        level50Button.setOnClickListener { sendLevel(50) }
        level75Button.setOnClickListener { sendLevel(75) }
        level100Button.setOnClickListener { sendLevel(100) }
        sosButton.setOnClickListener {
            startModeLoop(MagicshineMode.SOS)
        }
        blitzButton.setOnClickListener {
            startModeLoop(MagicshineMode.BLITZ)
        }
        disconnectButton.setOnClickListener {
            controller.stopRepeatingCommand()
            controller.disconnect()
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            controller.clearUiCallbacks()
        }
        super.onDestroy()
    }

    private fun connectIfPermitted() {
        if (!hasPermissions()) {
            ensurePermissions()
            Toast.makeText(this, "Grant Bluetooth permissions first", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentSelectedLampAddress == null) {
            Toast.makeText(this, "Select a lamp first", Toast.LENGTH_SHORT).show()
            return
        }
        controller.connect()
    }

    private fun sendIfPermitted(frame: String) {
        if (!hasPermissions()) {
            ensurePermissions()
            Toast.makeText(this, "Grant Bluetooth permissions first", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentSelectedLampAddress == null) {
            Toast.makeText(this, "Select a lamp first", Toast.LENGTH_SHORT).show()
            return
        }
        controller.send(frame)
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissions() {
        if (!hasPermissions()) permissionLauncher.launch(requiredPermissions())
    }

    private fun refreshUiFromController() {
        refreshLampSelectionUi()
        val connectionStatus = controller.currentConnectionStatus()
        val batteryStatus = controller.currentBatteryStatus()
        val temperatureStatus = controller.currentTemperatureStatus()
        val status = controller.currentStatus()

        if (currentConnectionStatus != connectionStatus) {
            currentConnectionStatus = connectionStatus
        }
        if (currentBatteryStatus != batteryStatus) {
            currentBatteryStatus = batteryStatus
            batteryView.text = batteryStatus
        }
        if (currentTemperatureStatus != temperatureStatus) {
            currentTemperatureStatus = temperatureStatus
            temperatureView.text = temperatureStatus
        }
        val displayStatus = displayStatus(status)
        if (currentDisplayStatus != displayStatus) {
            currentDisplayStatus = displayStatus
        }
        updateConnectButton()
    }

    private fun restoreSelectedLamp() {
        val savedAddress = prefs.getString(PREF_SELECTED_LAMP_ADDRESS, null)
        val savedName = prefs.getString(PREF_SELECTED_LAMP_NAME, null)
        currentSelectedLampAddress = savedAddress
        currentSelectedLampName = savedName
        controller.setPreferredAddress(savedAddress)
    }

    private fun saveSelectedLamp(address: String?, name: String? = null) {
        currentSelectedLampAddress = address
        currentSelectedLampName = name
        prefs.edit()
            .putString(PREF_SELECTED_LAMP_ADDRESS, address)
            .putString(PREF_SELECTED_LAMP_NAME, name)
            .apply()
    }

    private fun refreshLampSelectionUi() {
        val selectedLamp = controller.currentSelectedLamp()
        val candidates = controller.currentLampCandidates()
        val unsupportedCandidates = controller.currentUnsupportedLampCandidates()
        val preferredAddress = controller.currentPreferredAddress()
        currentSelectedLampAddress = preferredAddress
        if (selectedLamp != null && currentSelectedLampName != selectedLamp.name) {
            currentSelectedLampName = selectedLamp.name
            prefs.edit().putString(PREF_SELECTED_LAMP_NAME, selectedLamp.name).apply()
        }

        val hasSelection = preferredAddress != null
        chooserGate.visibility = if (hasSelection) android.view.View.GONE else android.view.View.VISIBLE
        controlPanel.visibility = if (hasSelection) android.view.View.VISIBLE else android.view.View.GONE
        changeLampButton.visibility = if (hasSelection) android.view.View.VISIBLE else android.view.View.GONE
        changeLampLabel.text = selectedLamp?.name ?: currentSelectedLampName ?: "Switch lamp"

        chooserHintView.text = if (candidates.isEmpty()) {
            if (unsupportedCandidates.isEmpty()) {
                "Searching for supported lamps"
            } else {
                "Select a supported lamp or allow another M2-B0 model"
            }
        } else {
            "Tap to select"
        }

        val signature = buildString {
            append(candidates.joinToString("|") { "${it.address}:${it.name}" })
            append("::")
            append(unsupportedCandidates.joinToString("|") { "${it.address}:${it.name}" })
        }
        if (signature == lastRenderedCandidateSignature) return
        lastRenderedCandidateSignature = signature

        lampCandidatesLayout.removeAllViews()
        candidates.forEach { candidate ->
            val button = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(44),
                ).also { it.topMargin = dpToPx(4) }
                text = formatLampCandidate(candidate)
                textSize = 11f
                setOnClickListener {
                    saveSelectedLamp(candidate.address, candidate.name)
                    controller.setPreferredAddress(candidate.address)
                    controller.startDiscovery(forceRestart = true)
                    refreshLampSelectionUi()
                }
            }
            lampCandidatesLayout.addView(button)
        }

        if (unsupportedCandidates.isNotEmpty()) {
            val title = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = dpToPx(8) }
                text = "Other M2-B0 models"
                textSize = 11f
            }
            lampCandidatesLayout.addView(title)

            unsupportedCandidates
                .distinctBy { it.name.lowercase() }
                .forEach { candidate ->
                    val button = Button(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dpToPx(44),
                        ).also { it.topMargin = dpToPx(4) }
                        text = "Allow ${candidate.name}"
                        textSize = 11f
                        setOnClickListener {
                            controller.approveDeviceName(candidate.name)
                            val approvedCandidate = controller.currentLampCandidates()
                                .firstOrNull { it.name.equals(candidate.name, ignoreCase = true) }
                            if (approvedCandidate != null) {
                                saveSelectedLamp(approvedCandidate.address, approvedCandidate.name)
                                controller.setPreferredAddress(approvedCandidate.address)
                            } else {
                                controller.startDiscovery(forceRestart = true)
                            }
                            refreshLampSelectionUi()
                        }
                    }
                    lampCandidatesLayout.addView(button)
                }
        }
    }

    private fun updateConnectButton() {
        connectLabelView.text = when (currentConnectionStatus) {
            "connected" -> "CONNECTED"
            "connecting" -> "CONNECT"
            else -> "CONNECT"
        }
        connectStateView.text = currentDisplayStatus
        val background = when {
            currentConnectionStatus == "connected" || currentDisplayStatus == "connected" ->
                R.drawable.bg_connect_connected
            currentDisplayStatus == "found" -> R.drawable.bg_connect_found
            else -> R.drawable.bg_connect_idle
        }
        connectButton.setBackgroundResource(background)
    }

    private fun updateBrightnessControls() {
        level25Button.setBackgroundResource(
            if (selectedLevelPercent == 25) R.drawable.bg_module_selected else R.drawable.bg_lamp_switch,
        )
        level50Button.setBackgroundResource(
            if (selectedLevelPercent == 50) R.drawable.bg_module_selected else R.drawable.bg_lamp_switch,
        )
        level75Button.setBackgroundResource(
            if (selectedLevelPercent == 75) R.drawable.bg_module_selected else R.drawable.bg_lamp_switch,
        )
        level100Button.setBackgroundResource(
            if (selectedLevelPercent == 100) R.drawable.bg_module_selected else R.drawable.bg_lamp_switch,
        )
    }

    private fun sendLevel(percent: Int) {
        controller.stopRepeatingCommand()
        if (selectedOutputTarget == OutputTarget.OFF) {
            selectedOutputTarget = if (selectedModule == MagicshineModule.MODULE_2) {
                OutputTarget.HIGH
            } else {
                OutputTarget.LOW
            }
        }
        selectedLevelPercent = percent
        updateOutputControls()
        updateBrightnessControls()
        sendIfPermitted(MagicshineProtocol.buildPresetFrame(selectedModule, percent))
    }

    private fun startModeLoop(mode: MagicshineMode) {
        controller.stopRepeatingCommand()
        selectedLevelPercent = null
        if (selectedOutputTarget == OutputTarget.OFF) {
            selectedOutputTarget = if (selectedModule == MagicshineModule.MODULE_2) {
                OutputTarget.HIGH
            } else {
                OutputTarget.LOW
            }
        }
        updateOutputControls()
        updateBrightnessControls()
        val module = selectedModule
        val frame = MagicshineProtocol.buildModeFrame(module, mode)
        sendIfPermitted(frame)
        controller.startRepeatingCommand(frame, 1500L)
    }

    private fun resendSelectedLevelForCurrentModule() {
        val percent = selectedLevelPercent ?: return
        sendIfPermitted(MagicshineProtocol.buildPresetFrame(selectedModule, percent))
    }

    private fun updateOutputControls() {
        val module1Selected = selectedOutputTarget == OutputTarget.LOW
        val module2Selected = selectedOutputTarget == OutputTarget.HIGH
        val offSelected = selectedOutputTarget == OutputTarget.OFF
        module1Button.setBackgroundResource(
            if (module1Selected) R.drawable.bg_module_selected else R.drawable.bg_module_idle,
        )
        module2Button.setBackgroundResource(
            if (module2Selected) R.drawable.bg_module_selected else R.drawable.bg_module_idle,
        )
        offButton.setBackgroundResource(
            if (offSelected) R.drawable.bg_module_selected else R.drawable.bg_module_idle,
        )
        module1Label.text = "LOW"
        module2Label.text = "HIGH"
    }

    private fun displayStatus(raw: String): String = when {
        raw.startsWith("seen[") -> "searching"
        raw.startsWith("discovery")
            || raw.startsWith("waiting for target")
            || raw.startsWith("scanning...")
            || raw.startsWith("search") -> "searching"
        raw.startsWith("target cached")
            || raw.startsWith("found") -> "found"
        raw.startsWith("connected")
            || raw.startsWith("sync telemetry")
            || raw.startsWith("writing")
            || raw.startsWith("write ok")
            || raw.startsWith("send requested")
            || raw == "connected" -> "connected"
        raw.startsWith("disconnect") || raw == "disconnected" -> "disconnected"
        raw.startsWith("missing bluetooth permissions") -> "permissions"
        raw.startsWith("ble error") || raw.startsWith("sync error") -> "error"
        raw.startsWith("no target") || raw.startsWith("no device") -> "searching"
        else -> raw
    }

    private fun formatLampCandidate(candidate: LampCandidate): String =
        "${candidate.name} · ${shortAddress(candidate.address)}"

    private fun shortAddress(address: String): String =
        address.takeLast(8)

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
