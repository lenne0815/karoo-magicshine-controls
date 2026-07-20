package com.lenne0815.magicshinesniffer

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.WriteType
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class MagicshineBleSniffer(
    context: Context,
    private val listener: (String) -> Unit,
) {
    companion object {
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val POLL_INTERVAL_MS = 5_000L

        const val QUERY_TEMPERATURE = "DE06A100A7ED"
        const val QUERY_BATTERY = "DE06A400A2ED"
        const val QUERY_PROFILES = "DE06A500A3ED"
        const val QUERY_ENDURANCE = "DE06A900AFED"
        const val QUERY_AB = "DE06AB00ADED"
        const val QUERY_AC = "DE06AC00AAED"
        const val QUERY_AD = "DE06AD00ABED"
        private const val MODULE_2_FULL_POWER = "DE14A2010200010A010164000000000000BB61ED"
        private const val OFFICIAL_A6 = "DE07A601EF4FED"
        private const val OFFICIAL_STEADY = "DE14A2010101010A000150000000000000BB56ED"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val centralManager by lazy { CentralManager.Factory.native(appContext, scope) }
    private val bluetoothManager by lazy { appContext.getSystemService(BluetoothManager::class.java) }
    private val operationMutex = Mutex()
    private val analyzer = FrameAnalyzer()
    private val targetService = Uuid.parse("0000FFE1-0000-1000-8000-00805f9b34fb")
    private val targetCharacteristic = Uuid.parse("0000FFE0-0000-1000-8000-00805f9b34fb")
    private val connectionOptions = CentralManager.ConnectionOptions.Direct(
        timeout = 8.seconds,
        retry = 0,
        retryDelay = 1.seconds,
    )

    @Volatile private var peripheral: Peripheral? = null
    @Volatile private var characteristic: RemoteCharacteristic? = null
    @Volatile private var connectJob: Job? = null
    @Volatile private var notificationJob: Job? = null
    @Volatile private var pollJob: Job? = null

    fun connect() {
        if (connectJob?.isActive == true) return
        if (!hasPermissions()) {
            line("ERROR missing Bluetooth permissions")
            return
        }
        if (bluetoothManager?.adapter?.isEnabled != true) {
            line("ERROR Bluetooth is off")
            return
        }
        connectJob = scope.launch {
            operationMutex.withLock {
                runCatching { connectInternal() }
                    .onFailure { line("ERROR connect ${it::class.java.simpleName}: ${it.message}") }
            }
        }.also { job -> job.invokeOnCompletion { if (connectJob === job) connectJob = null } }
    }

    fun disconnect() {
        stopPolling()
        scope.launch {
            operationMutex.withLock {
                notificationJob?.cancel()
                notificationJob = null
                characteristic = null
                peripheral?.let { target ->
                    runCatching { if (target.state.value is ConnectionState.Connected) target.disconnect() }
                }
                peripheral = null
                line("DISCONNECTED")
            }
        }
    }

    fun send(label: String, frameHex: String) {
        scope.launch {
            operationMutex.withLock {
                writeInternal(label, frameHex)
            }
        }
    }

    fun runSupportSweep() {
        scope.launch {
            operationMutex.withLock {
                line("========== SUPPORT SWEEP ==========")
                listOf(
                    "A1 TEMP" to QUERY_TEMPERATURE,
                    "A4 CANDIDATE" to QUERY_BATTERY,
                    "A5 PROFILES" to QUERY_PROFILES,
                    "A9 RUNTIME" to QUERY_ENDURANCE,
                    "AB" to QUERY_AB,
                    "AC" to QUERY_AC,
                    "AD" to QUERY_AD,
                ).forEach { (label, frame) ->
                    writeInternal(label, frame)
                    delay(350)
                }
            }
        }
    }

    fun runGattSurvey() {
        scope.launch {
            operationMutex.withLock {
                val target = peripheral
                if (target?.state?.value !is ConnectionState.Connected) {
                    line("ERROR GATT READS ignored: not connected")
                    return@withLock
                }
                val services = withTimeoutOrNull(5_000L) {
                    target.services().first { it.isNotEmpty() }
                }
                if (services == null) {
                    line("ERROR GATT READS: no services")
                    return@withLock
                }

                line("========== GATT READS ==========")
                services.forEach { service ->
                    line("GATT service=${service.uuid} chars=${service.characteristics.size}")
                    service.characteristics.forEach { remoteChar ->
                        val properties = remoteChar.properties.joinToString("+")
                        line("GATT char=${remoteChar.uuid} properties=$properties")
                        if (CharacteristicProperty.READ in remoteChar.properties) {
                            runCatching { remoteChar.read() }
                                .onSuccess { value ->
                                    line("GATT READ ${remoteChar.uuid} ${value.toHex()}")
                                }
                                .onFailure { error ->
                                    line("ERROR GATT READ ${remoteChar.uuid} ${error::class.java.simpleName}: ${error.message}")
                                }
                        }
                    }
                }
            }
        }
    }

    fun runOfficialSequence() {
        scope.launch {
            operationMutex.withLock {
                line("========== OFFICIAL SEQUENCE (changes low beam briefly) ==========")
                listOf(
                    "A1" to QUERY_TEMPERATURE,
                    "A6" to OFFICIAL_A6,
                    "STEADY LOW" to OFFICIAL_STEADY,
                    "A4" to QUERY_BATTERY,
                ).forEach { (label, frame) ->
                    writeInternal(label, frame)
                    delay(180)
                }
            }
        }
    }

    fun startBatteryPolling() {
        stopPolling()
        pollJob = scope.launch {
            line("POLL A4 every ${POLL_INTERVAL_MS / 1000}s")
            while (true) {
                operationMutex.withLock { writeInternal("A4 POLL", QUERY_BATTERY) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun startFullPowerDischarge() {
        stopPolling()
        pollJob = scope.launch {
            operationMutex.withLock { writeInternal("DRAIN FULL POWER", MODULE_2_FULL_POWER) }
            delay(500)
            line("DRAIN TEST A4 every 5s; support telemetry every 60s")
            var pollCount = 0
            while (true) {
                if (peripheral?.state?.value !is ConnectionState.Connected) {
                    line("DRAIN COMPLETE OR DISCONNECTED")
                    break
                }
                operationMutex.withLock {
                    writeInternal("A4 DRAIN", QUERY_BATTERY)
                    if (pollCount % 12 == 0) {
                        writeInternal("A1 DRAIN", QUERY_TEMPERATURE)
                        delay(200)
                        writeInternal("AB DRAIN", QUERY_AB)
                        delay(200)
                        writeInternal("AC DRAIN", QUERY_AC)
                    }
                }
                pollCount++
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        val wasPolling = pollJob?.isActive == true
        pollJob?.cancel()
        pollJob = null
        if (wasPolling) line("POLL STOPPED")
    }

    fun close() {
        pollJob?.cancel()
        notificationJob?.cancel()
        connectJob?.cancel()
        characteristic = null
        peripheral = null
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun connectInternal() {
        val existing = peripheral
        if (existing?.state?.value is ConnectionState.Connected && characteristic != null) {
            line("CONNECTED already ${existing.name ?: existing.address}")
            return
        }
        line("SCAN for M2-B0/M1-B0 lamp")
        val result = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            centralManager.scan().first { scanResult ->
                val name = (scanResult.advertisingData.name ?: scanResult.peripheral.name)
                    ?.replace("\u0000", "")
                    ?.trim()
                    .orEmpty()
                name.startsWith("M2-B0", true) ||
                    name.startsWith("M2-BO", true) ||
                    name.startsWith("M1-B0", true) ||
                    name.startsWith("M1-BO", true)
            }
        }
        if (result == null) {
            line("ERROR no supported lamp found")
            return
        }

        val target = result.peripheral
        peripheral = target
        val cleanName = (result.advertisingData.name ?: target.name)
            ?.replace("\u0000", "")
            ?.replace("\uFFFD", "")
            ?.trim()
            .orEmpty()
        line("FOUND $cleanName ${target.address} rssi=${result.rssi}")
        centralManager.connect(target, connectionOptions)
        line("GATT CONNECTED ${target.address}")
        val targetChar = awaitCharacteristic(target)
        if (targetChar == null) {
            line("ERROR FFE1/FFE0 characteristic missing")
            return
        }
        characteristic = targetChar
        notificationJob?.cancel()
        notificationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                targetChar.subscribe().collect { frame -> line(analyzer.analyze(frame)) }
            }.onFailure { line("ERROR notification ${it::class.java.simpleName}: ${it.message}") }
        }
        delay(150)
        line("READY notifications enabled; use query buttons")
    }

    private suspend fun awaitCharacteristic(target: Peripheral): RemoteCharacteristic? {
        val services = withTimeoutOrNull(10_000L) {
            target.services(listOf(targetService)).first { discovered ->
                line("SERVICES update count=${discovered.size}")
                discovered.any { service ->
                    service.characteristics.any { it.uuid == targetCharacteristic }
                }
            }
        } ?: return null

        return services.firstNotNullOfOrNull { service ->
            service.characteristics.firstOrNull { it.uuid == targetCharacteristic }
        }
    }

    private suspend fun writeInternal(label: String, frameHex: String) {
        val target = peripheral
        val targetChar = characteristic
        if (target?.state?.value !is ConnectionState.Connected || targetChar == null) {
            line("ERROR $label ignored: not connected")
            return
        }
        line("TX $label $frameHex")
        runCatching { targetChar.write(frameHex.hexToBytes(), WriteType.WITH_RESPONSE) }
            .onFailure { line("ERROR TX $label ${it::class.java.simpleName}: ${it.message}") }
    }

    private fun hasPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun line(message: String) = listener(message)
}
