package com.lenne0815.karoomagicshine.extension

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.lenne0815.karoomagicshine.MagicshineProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.WriteType
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class LampCandidate(
    val address: String,
    val name: String,
)

@OptIn(ExperimentalUuidApi::class)
class MagicshineBleController(
    context: Context,
    private var onStatus: (String) -> Unit = {},
    private var onConnectionStatus: (String) -> Unit = {},
    private var onBatteryStatus: (String) -> Unit = {},
    private var onTemperatureStatus: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "MagicshineBle"
        val SUPPORTED_NAME_PREFIXES = setOf(
            "M2-B0",
            "M2-BO",
            "M1-B0",
            "M1-BO",
        )
        private const val PREFS_NAME = "magicshine_prefs"
        private const val PREF_SELECTED_LAMP_ADDRESS = "selected_lamp_address"
    }

    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val centralManager by lazy { CentralManager.Factory.native(appContext, scope) }

    private val targetService = Uuid.parse("0000FFE1-0000-1000-8000-00805f9b34fb")
    private val targetChar = Uuid.parse("0000FFE0-0000-1000-8000-00805f9b34fb")

    private val connectionOptions by lazy {
        CentralManager.ConnectionOptions.Direct(timeout = 8.seconds, retry = 0, retryDelay = 1.seconds)
    }

    @Volatile private var discoveryJob: Job? = null
    @Volatile private var lastPeripheral: Peripheral? = null
    @Volatile private var lastTargetSeenAtMs: Long = 0L
    @Volatile private var seenCount: Int = 0
    @Volatile private var lastSeenTag: String = "none"
    @Volatile private var lastPublishedStatus: String? = null
    @Volatile private var lastPublishedConnectionStatus: String? = null
    @Volatile private var lastPublishedBatteryStatus: String? = null
    @Volatile private var lastPublishedTemperatureStatus: String? = null
    @Volatile private var lastBatteryStatusAtMs: Long = 0L
    @Volatile private var lastTemperatureStatusAtMs: Long = 0L
    @Volatile private var preferredAddress: String? = prefs.getString(PREF_SELECTED_LAMP_ADDRESS, null)
    @Volatile private var notificationJob: Job? = null
    @Volatile private var repeatingCommandJob: Job? = null
    @Volatile private var connectJob: Job? = null
    @Volatile private var telemetryBootstrapJob: Job? = null
    @Volatile private var observingAddress: String? = null
    private val operationMutex = Mutex()
    private val candidateLock = Any()
    private val knownCandidates = LinkedHashMap<String, LampCandidate>()
    private val knownPeripherals = LinkedHashMap<String, Peripheral>()

    fun startDiscovery(forceRestart: Boolean = false) {
        clearStalePublishedConnectionState()
        if (!forceRestart && lastPeripheral?.state?.value is ConnectionState.Connected) {
            return
        }
        if (forceRestart) {
            discoveryJob?.cancel()
            discoveryJob = null
            lastPeripheral = null
            lastTargetSeenAtMs = 0L
            seenCount = 0
            lastSeenTag = "none"
            synchronized(candidateLock) {
                knownCandidates.clear()
                knownPeripherals.clear()
            }
        } else if (discoveryJob?.isActive == true) {
            return
        }
        if (!hasBlePermissions()) {
            publishStatus("missing bluetooth permissions")
            return
        }

        discoveryJob = scope.launch {
            seenCount = 0
            lastSeenTag = "none"
            publishStatus("searching")
            try {
                centralManager
                    .scan { Any { } }
                    .collect { result ->
                        val p = result.peripheral
                        val name = p.name ?: "<unnamed>"
                        val tag = "$name/${p.address}"
                        seenCount += 1
                        lastSeenTag = tag

                        if (matchesSupportedFamily(name)) {
                            val candidate = LampCandidate(address = p.address, name = name)
                            val preferred = preferredAddress
                            synchronized(candidateLock) {
                                knownCandidates[p.address] = candidate
                                knownPeripherals[p.address] = p
                            }
                            if (preferred != null && preferred == p.address) {
                                val isNewTarget = lastPeripheral?.address != p.address
                                lastPeripheral = p
                                lastTargetSeenAtMs = System.currentTimeMillis()
                                val shouldPublishFound =
                                    isNewTarget ||
                                        lastPublishedStatus == null ||
                                        lastPublishedStatus in setOf("searching", "disconnected", "idle")
                                if (shouldPublishFound) {
                                    publishStatus("found")
                                }
                            }
                        }
                    }
            } catch (t: Throwable) {
                publishStatus("discovery error: ${t::class.java.simpleName}")
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    fun setPreferredAddress(address: String?) {
        preferredAddress = address
        prefs.edit().putString(PREF_SELECTED_LAMP_ADDRESS, address).apply()
        if (address == null) {
            clearActiveConnectionState(clearCachedPeripheral = true)
            publishConnectionStatus("disconnected")
            resetTelemetryStatus()
            publishStatus("searching")
            return
        }
        synchronized(candidateLock) {
            knownPeripherals[address]?.let { peripheral ->
                lastPeripheral = peripheral
                lastTargetSeenAtMs = System.currentTimeMillis()
            }
        }
    }

    fun currentPreferredAddress(): String? = preferredAddress

    fun currentLampCandidates(): List<LampCandidate> = synchronized(candidateLock) {
        knownCandidates.values.toList()
    }

    fun currentSelectedLamp(): LampCandidate? {
        val selected = preferredAddress ?: return null
        return synchronized(candidateLock) { knownCandidates[selected] }
            ?: lastPeripheral?.let { LampCandidate(it.address, it.name ?: "Magicshine") }
    }

    private fun preferredPeripheral(): Peripheral? {
        val selected = preferredAddress ?: return null
        return synchronized(candidateLock) { knownPeripherals[selected] } ?: lastPeripheral?.takeIf { it.address == selected }
    }

    fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch {
            operationMutex.withLock {
                if (preferredAddress == null) {
                    publishConnectionStatus("no device")
                    publishStatus("searching")
                    return@withLock
                }
                publishConnectionStatus("connecting")
                val cached = preferredPeripheral().also { if (it != null) lastPeripheral = it }
                val isConnected = cached?.state?.value is ConnectionState.Connected
                if (!isConnected && cached == null) {
                    startDiscovery()
                    startDiscovery(forceRestart = true)
                }

                val target = awaitTarget() ?: run {
                    Log.d(
                        TAG,
                        "awaitTarget timeout preferred=$preferredAddress seenCount=$seenCount lastSeenTag=$lastSeenTag",
                    )
                    publishConnectionStatus("no device")
                    return@withLock
                }

                try {
                    publishStatus("found")
                    ensureConnected(target)
                    publishStatus("connected")
                    scheduleTelemetryBootstrap(target)
                } catch (t: Throwable) {
                    cleanupAfterConnectionFailure(target)
                    publishStatus("ble error: ${t::class.java.simpleName}")
                    publishConnectionStatus("fehler")
                }
            }
        }.also { job ->
            job.invokeOnCompletion { if (connectJob === job) connectJob = null }
        }
    }

    fun send(frameHex: String) {
        scope.launch {
            operationMutex.withLock {
                sendInternal(frameHex)
            }
        }
    }

    fun startRepeatingCommand(frameHex: String, intervalMs: Long = 1500L) {
        stopRepeatingCommand()
        repeatingCommandJob = scope.launch {
            while (true) {
                operationMutex.withLock {
                    sendInternal(frameHex)
                }
                delay(intervalMs)
            }
        }
    }

    fun stopRepeatingCommand() {
        repeatingCommandJob?.cancel()
        repeatingCommandJob = null
    }

    fun refreshTelemetry() {
        // Telemetry polling is disabled until the protocol is understood well enough
        // to avoid interfering with active light control.
    }

    fun disconnect() {
        scope.launch {
            operationMutex.withLock {
                stopRepeatingCommand()
                val target = lastPeripheral
                if (target == null) {
                    publishConnectionStatus("disconnected")
                    clearActiveConnectionState(clearCachedPeripheral = true)
                    resetTelemetryStatus()
                    return@withLock
                }

                if (target.state.value !is ConnectionState.Connected) {
                    publishConnectionStatus("disconnected")
                    clearActiveConnectionState(clearCachedPeripheral = true)
                    resetTelemetryStatus()
                    return@withLock
                }

                try {
                    publishStatus("disconnecting")
                    writeFrameWithRetry(
                        target,
                        MagicshineProtocol.buildPresetFrame(
                            com.lenne0815.karoomagicshine.MagicshineModule.MODULE_1,
                            0,
                        ),
                    )
                    delay(40)
                    writeFrameWithRetry(
                        target,
                        MagicshineProtocol.buildPresetFrame(
                            com.lenne0815.karoomagicshine.MagicshineModule.MODULE_2,
                            0,
                        ),
                    )
                    delay(60)
                    target.disconnect()
                    cancelActiveJobs()
                    clearActiveConnectionState(clearCachedPeripheral = true)
                    publishStatus("disconnected")
                    publishConnectionStatus("disconnected")
                    resetTelemetryStatus()
                } catch (t: Throwable) {
                    publishStatus("disconnect error: ${t::class.java.simpleName}")
                }
            }
        }
    }

    private suspend fun ensureConnected(peripheral: Peripheral) {
        if (peripheral.state.value !is ConnectionState.Connected) {
            publishStatus("connecting: ${peripheral.name ?: peripheral.address}")
            publishConnectionStatus("connecting")
            centralManager.connect(peripheral, connectionOptions)
            discoveryJob?.cancel()
            discoveryJob = null
            publishStatus("connected")
            publishConnectionStatus("connected")
        } else {
            discoveryJob?.cancel()
            discoveryJob = null
            publishStatus("connected")
            publishConnectionStatus("connected")
        }
        waitForTargetCharacteristic(peripheral)
        ensureNotificationObservation(peripheral)
        waitUntil(timeoutMs = 40, stepMs = 8) {
            notificationJob?.isActive == true && findTargetCharacteristic(peripheral) != null
        }
    }

    private suspend fun cleanupAfterConnectionFailure(peripheral: Peripheral) {
        try {
            cancelActiveJobs()
            peripheral.disconnect()
        } catch (_: Throwable) {
        } finally {
            if (lastPeripheral?.address == peripheral.address) {
                lastPeripheral = null
            }
            clearActiveConnectionState(clearCachedPeripheral = false)
            stopDiscovery()
            publishConnectionStatus("disconnected")
        }
    }

    private suspend fun requestTelemetry(peripheral: Peripheral) {
        val telemetryStartedAtMs = System.currentTimeMillis()
        publishStatus("sync telemetry")
        if (!writeFrameWithRetry(peripheral, "DE06A100A7ED")) return
        delay(70)
        if (!writeFrameWithRetry(peripheral, "DE07A601EF4FED")) return
        delay(90)
        if (!writeFrameWithRetry(
                peripheral,
                MagicshineProtocol.buildModeFrame(
                    com.lenne0815.karoomagicshine.MagicshineModule.MODULE_1,
                    com.lenne0815.karoomagicshine.MagicshineMode.STEADY,
                ),
            )
        ) return
        delay(70)
        if (!writeFrameWithRetry(peripheral, "DE06A400A2ED")) return
        waitUntil(timeoutMs = 140, stepMs = 10) {
            lastBatteryStatusAtMs > telemetryStartedAtMs || lastTemperatureStatusAtMs > telemetryStartedAtMs
        }
        writeFrameWithRetry(
            peripheral,
            MagicshineProtocol.buildPresetFrame(com.lenne0815.karoomagicshine.MagicshineModule.MODULE_1, 0),
        )
    }

    private fun scheduleTelemetryBootstrap(peripheral: Peripheral) {
        telemetryBootstrapJob?.cancel()
        telemetryBootstrapJob = scope.launch {
            delay(180)
            operationMutex.withLock {
                if (peripheral.state.value !is ConnectionState.Connected) return@withLock
                if (lastPeripheral?.address != peripheral.address) return@withLock
                runCatching { requestTelemetry(peripheral) }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (telemetryBootstrapJob === job) telemetryBootstrapJob = null
            }
        }
    }

    private suspend fun awaitTarget(): Peripheral? {
        var p = preferredPeripheral().also { if (it != null) lastPeripheral = it } ?: lastPeripheral
        if (p?.state?.value is ConnectionState.Connected) return p
        if (p == null) {
            publishStatus("searching")
            startDiscovery()
            for (i in 0 until 48) {
                delay(25)
                p = preferredPeripheral().also { if (it != null) lastPeripheral = it } ?: lastPeripheral
                if (p?.state?.value is ConnectionState.Connected || p != null) break
                if ((i == 11 || i == 23) && seenCount == 0) {
                    startDiscovery(forceRestart = true)
                }
                if (i % 12 == 11) {
                    publishStatus("searching")
                }
            }
        }
        return p
    }

    private suspend fun writeFrame(peripheral: Peripheral, frameHex: String) {
        val characteristic = findTargetCharacteristic(peripheral)
        if (characteristic == null) {
            return
        }

        characteristic.write(frameHex.hexToBytes(), WriteType.WITH_RESPONSE)
    }

    private suspend fun writeFrameWithRetry(
        peripheral: Peripheral,
        frameHex: String,
        attempts: Int = 8,
        delayMs: Long = 60,
    ): Boolean {
        repeat(attempts) { attempt ->
            val characteristic = findTargetCharacteristic(peripheral)
            if (characteristic != null) {
                characteristic.write(frameHex.hexToBytes(), WriteType.WITH_RESPONSE)
                return true
            }
            if (attempt < attempts - 1) {
                delay(delayMs)
            }
        }
        return false
    }

    private suspend fun ensureNotificationObservation(peripheral: Peripheral) {
        if (observingAddress == peripheral.address && notificationJob?.isActive == true) return

        notificationJob?.cancel()
        val characteristic = findTargetCharacteristic(peripheral) ?: return
        observingAddress = peripheral.address
        notificationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                characteristic.subscribe().collect { data ->
                    val hex = data.toHexString()
                    parseNotifyFrame(hex)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun findTargetCharacteristic(peripheral: Peripheral): RemoteCharacteristic? {
        val servicesFlow = peripheral.services(listOf(targetService))
        var service = servicesFlow.value.firstOrNull()
        if (service == null) {
            repeat(6) {
                delay(60)
                service = servicesFlow.value.firstOrNull()
                if (service != null) return@repeat
            }
        }

        if (service == null) {
            return null
        }

        val characteristic = service!!.characteristics.firstOrNull { it.uuid == targetChar }
        if (characteristic == null) {
            return null
        }
        return characteristic
    }

    private suspend fun waitForTargetCharacteristic(peripheral: Peripheral): RemoteCharacteristic? {
        repeat(8) {
            val characteristic = findTargetCharacteristic(peripheral)
            if (characteristic != null) return characteristic
            delay(40)
        }
        return null
    }

    private fun parseNotifyFrame(frameHex: String) {
        MagicshineProtocol.parseBatteryPercent(frameHex)?.let {
            publishBatteryStatus("$it%")
        }
        MagicshineProtocol.parseTemperatureCelsius(frameHex)?.let { publishTemperatureStatus("${it}C") }
    }

    private fun matchesSupportedFamily(name: String): Boolean =
        SUPPORTED_NAME_PREFIXES.any { prefix -> name.startsWith(prefix, ignoreCase = true) }

    private suspend fun waitUntil(
        timeoutMs: Long,
        stepMs: Long,
        predicate: suspend () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            delay(stepMs)
        }
        return predicate()
    }

    private fun String.hexToBytes(): ByteArray {
        val clean = uppercase().replace(" ", "")
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { eachByte -> "%02X".format(eachByte) }

    private fun hasBlePermissions(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun publishStatus(message: String) {
        val normalized = normalizeStatus(message) ?: return
        if (lastPublishedStatus == "connected" && normalized in setOf("searching", "found", "connecting")) {
            return
        }
        if (lastPublishedConnectionStatus == "connected" && normalized in setOf("searching", "found", "connecting")) {
            return
        }
        if (lastPublishedStatus == normalized) return
        lastPublishedStatus = normalized
        onStatus(normalized)
    }

    private fun normalizeStatus(message: String): String? = when {
        message.startsWith("seen[") -> null
        message.startsWith("service missing") -> null
        message.startsWith("characteristic missing") -> null
        message.startsWith("target cached") -> "found"
        message == "found" -> "found"
        message.startsWith("discovery")
            || message.startsWith("waiting for target")
            || message.startsWith("scanning...")
            || message == "searching" -> "searching"
        message.startsWith("no target")
            || message.startsWith("no device") -> "disconnected"
        message.startsWith("connecting") -> "connecting"
        message.startsWith("connected")
            || message.startsWith("sync telemetry") -> "connected"
        message.startsWith("disconnect")
            || message.startsWith("already disconnected") -> "disconnected"
        message.startsWith("missing bluetooth permissions") -> "permissions"
        message.startsWith("ble error")
            || message.startsWith("sync error")
            || message.startsWith("discovery error") -> "error"
        else -> message
    }

    private fun publishConnectionStatus(message: String) {
        if (lastPublishedConnectionStatus == message) return
        lastPublishedConnectionStatus = message
        onConnectionStatus(message)
    }

    private fun publishBatteryStatus(message: String) {
        if (lastPublishedBatteryStatus == message) return
        lastPublishedBatteryStatus = message
        lastBatteryStatusAtMs = System.currentTimeMillis()
        onBatteryStatus(message)
    }

    private fun publishTemperatureStatus(message: String) {
        if (lastPublishedTemperatureStatus == message) return
        lastPublishedTemperatureStatus = message
        lastTemperatureStatusAtMs = System.currentTimeMillis()
        onTemperatureStatus(message)
    }

    fun currentStatus(): String = lastPublishedStatus ?: "idle"

    fun currentConnectionStatus(): String = lastPublishedConnectionStatus ?: "disconnected"

    fun currentBatteryStatus(): String = lastPublishedBatteryStatus ?: "?"

    fun currentTemperatureStatus(): String = lastPublishedTemperatureStatus ?: "?"

    fun hasLiveConnection(): Boolean {
        val peripheral = preferredPeripheral() ?: lastPeripheral
        return peripheral?.state?.value is ConnectionState.Connected
    }

    fun hasConnectInFlight(): Boolean = connectJob?.isActive == true

    fun clearStalePublishedConnectionState() {
        if (!hasConnectInFlight() && !hasLiveConnection() && lastPublishedConnectionStatus != "disconnected") {
            publishConnectionStatus("disconnected")
        }
    }

    private fun cancelActiveJobs() {
        notificationJob?.cancel()
        notificationJob = null
        repeatingCommandJob?.cancel()
        repeatingCommandJob = null
        telemetryBootstrapJob?.cancel()
        telemetryBootstrapJob = null
        connectJob = null
    }

    private fun clearActiveConnectionState(clearCachedPeripheral: Boolean) {
        observingAddress = null
        lastTargetSeenAtMs = 0L
        lastSeenTag = "none"
        if (clearCachedPeripheral) {
            lastPeripheral = null
        }
    }

    private fun resetTelemetryStatus() {
        publishBatteryStatus("?")
        publishTemperatureStatus("?")
    }

    private suspend fun sendInternal(frameHex: String) {
        if (preferredAddress == null) {
            publishConnectionStatus("no device")
            publishStatus("searching")
            return
        }
        val cached = preferredPeripheral().also { if (it != null) lastPeripheral = it } ?: lastPeripheral
        val isConnected = cached?.state?.value is ConnectionState.Connected
        if (!isConnected && cached == null) {
            startDiscovery()
            startDiscovery(forceRestart = true)
        }

        val target = awaitTarget()
        if (target == null) {
            publishStatus("searching")
            publishConnectionStatus("no device")
            return
        }

        try {
            ensureConnected(target)
            writeFrame(target, frameHex)
        } catch (t: Throwable) {
            cleanupAfterConnectionFailure(target)
            publishStatus("ble error: ${t::class.java.simpleName}")
        }
    }
}
