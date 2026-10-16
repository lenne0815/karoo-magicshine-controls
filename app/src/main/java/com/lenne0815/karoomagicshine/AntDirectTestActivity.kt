package com.lenne0815.karoomagicshine

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lenne0815.karoomagicshine.extension.MagicshineBleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AntDirectTestActivity : AppCompatActivity() {
    companion object {
        private const val HORI_ANT_DEVICE_ID = "39269-35-5"
        private const val HORI_BLE_ADDRESS = "F9:0B:53:A0:34:93"
        private const val PREFS = "hori_controller"
        private const val LAST_MODE = "last_mode"
    }

    private lateinit var ant: KarooLightControl
    private lateinit var ble: MagicshineBleController
    private lateinit var status: TextView
    private lateinit var modeStatus: TextView
    private var normalMode = "HIGH"
    private var highBeam = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) connectBle() else status.text = "Bluetooth permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        normalMode = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_MODE, "HIGH") ?: "HIGH"
        ant = KarooLightControl(this).also { it.bind() }
        ble = MagicshineBleController(
            this,
            onConnectionStatus = { s -> runOnUiThread { status.text = "High beam BLE: " + s.uppercase() } }
        ).also { it.setPreferredAddress(HORI_BLE_ADDRESS) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18, 14, 18, 18)
        }
        root.addView(TextView(this).apply {
            text = "HORI 1300 PRO"
            textSize = 21f
            gravity = Gravity.CENTER
            setPadding(6, 6, 6, 4)
        })
        modeStatus = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(6, 0, 6, 4)
        }
        root.addView(modeStatus)
        status = TextView(this).apply {
            text = "High beam BLE: starting"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(6, 0, 6, 10)
        }
        root.addView(status)

        addMode(root, "OFF", "OFF", "OFF")
        addMode(root, "LOW", "STEADY4", "LOW")
        addMode(root, "MED", "STEADY3", "MED")
        addMode(root, "HIGH", "STEADY2", "HIGH")
        root.addView(Button(this).apply {
            text = "HIGH BEAM"
            textSize = 18f
            isAllCaps = false
            setOnClickListener { toggleHighBeam() }
        }, params())

        setContentView(root)
        updateModeStatus()
        if (hasBlePermissions()) connectBle() else requestBlePermissions()
    }

    private fun params() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 88
    ).apply { bottomMargin = 9 }

    private fun addMode(root: LinearLayout, label: String, antMode: String, localMode: String) {
        root.addView(Button(this).apply {
            text = label
            textSize = 18f
            isAllCaps = false
            setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = ant.setLightMode(HORI_ANT_DEVICE_ID, antMode)
                    if (ok) {
                        if (localMode != "OFF") {
                            normalMode = localMode
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_MODE, normalMode).apply()
                        }
                        highBeam = false
                    }
                    runOnUiThread {
                        updateModeStatus(if (ok) null else "ANT unavailable")
                    }
                }
            }
        }, params())
    }

    private fun toggleHighBeam() {
        if (!ble.hasLiveConnection()) {
            status.text = "High beam BLE: reconnecting"
            connectBle()
            return
        }
        if (!highBeam) {
            // Proven full-output sequence: claim constant-on mode, then enable high beam.
            ble.sendHoriControl(
                listOf(
                    MagicshineProtocol.buildHoriControlMode(15),
                    MagicshineProtocol.buildHoriControlBeam(true),
                )
            )
            highBeam = true
        } else {
            ble.sendHoriControl(listOf(MagicshineProtocol.buildHoriControlBeam(false)))
            highBeam = false
        }
        updateModeStatus()
    }

    private fun updateModeStatus(error: String? = null) {
        modeStatus.text = when {
            error != null -> error
            highBeam -> "Mode: HIGH BEAM"
            else -> "Mode: $normalMode"
        }
    }

    private fun connectBle() {
        status.text = "High beam BLE: connecting"
        ble.startDiscovery(forceRestart = true)
        ble.connectHoriControlOnly { ok ->
            runOnUiThread {
                status.text = if (ok) "High beam BLE: READY" else "High beam BLE: connection failed"
            }
        }
    }

    private fun hasBlePermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestBlePermissions() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionLauncher.launch(required)
    }

    override fun onDestroy() {
        ant.unbind()
        super.onDestroy()
    }
}
