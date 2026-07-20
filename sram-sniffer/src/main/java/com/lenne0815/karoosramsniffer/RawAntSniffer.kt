package com.lenne0815.karoosramsniffer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.dsi.ant.AntService
import com.dsi.ant.channel.AntChannel
import com.dsi.ant.channel.AntChannelProvider
import com.dsi.ant.channel.Capabilities
import com.dsi.ant.channel.IAntChannelEventHandler
import com.dsi.ant.channel.PredefinedNetwork
import com.dsi.ant.message.ChannelId
import com.dsi.ant.message.ChannelType
import com.dsi.ant.message.ExtendedAssignment
import com.dsi.ant.message.ExtendedData
import com.dsi.ant.message.LibConfig
import com.dsi.ant.message.fromant.AcknowledgedDataMessage
import com.dsi.ant.message.fromant.BroadcastDataMessage
import com.dsi.ant.message.fromant.ChannelEventMessage
import com.dsi.ant.message.fromant.ChannelResponseMessage
import com.dsi.ant.message.fromant.ChannelStatusMessage
import com.dsi.ant.message.fromant.MessageFromAntType
import com.dsi.ant.message.ipc.AntMessageParcel
import java.util.Locale
import kotlin.math.roundToInt

class RawAntSniffer(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onSnifferLine(line: String)
    }

    companion object {
        private const val RF_FREQUENCY_ANT_PLUS = 57
        private const val RETRY_MS = 2_000L
        private val LIB_CONFIG = LibConfig(true, true, false)
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val previousPayloadByDevice = mutableMapOf<String, ByteArray>()

    private var disposed = false
    private var receiverRegistered = false
    private var antServiceBound = false
    private var antService: AntService? = null
    private var channelProvider: AntChannelProvider? = null
    private var scanChannel: AntChannel? = null
    private var scanRequested = false

    private val antServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
            line("ANT service connected")
            if (binder == null) {
                line("ANT service binder was null")
                scheduleRetry()
                return
            }
            runCatching {
                antService = AntService(binder)
                channelProvider = antService?.getChannelProvider()
                startScanInternal()
            }.onFailure { throwable ->
                line("Unable to get ANT channel provider: ${throwable.message}")
                scheduleRetry()
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            line("ANT service disconnected")
            channelProvider = null
            antService = null
            closeScanChannel()
            if (!disposed) {
                scheduleRetry()
            }
        }
    }

    private val providerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AntChannelProvider.ACTION_CHANNEL_PROVIDER_STATE_CHANGED) {
                return
            }
            val channels = intent.getIntExtra(AntChannelProvider.NUM_CHANNELS_AVAILABLE, 0)
            val legacy = intent.getBooleanExtra(AntChannelProvider.LEGACY_INTERFACE_IN_USE, false)
            line("ANT provider state: channels=$channels legacy=$legacy")
            startScanInternal()
        }
    }

    fun start() {
        if (scanRequested) {
            return
        }
        disposed = false
        scanRequested = true
        registerProviderReceiver()
        bindAntService()
        startScanInternal()
    }

    fun stop() {
        scanRequested = false
        disposed = true
        handler.removeCallbacksAndMessages(null)
        closeScanChannel()
        if (antServiceBound) {
            runCatching { appContext.unbindService(antServiceConnection) }
            antServiceBound = false
        }
        unregisterProviderReceiver()
        antService = null
        channelProvider = null
        previousPayloadByDevice.clear()
        line("Sniffer stopped")
    }

    private fun bindAntService() {
        if (disposed || antServiceBound) {
            return
        }
        antServiceBound = AntService.bindService(appContext, antServiceConnection)
        line("ANT service bind requested: bound=$antServiceBound")
        if (!antServiceBound) {
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (disposed || !scanRequested) {
            return
        }
        val jitter = (RETRY_MS * (1.0 + Math.random())).roundToInt().toLong()
        handler.postDelayed({
            if (!antServiceBound) {
                bindAntService()
            }
            startScanInternal()
        }, jitter)
    }

    private fun startScanInternal() {
        if (disposed || !scanRequested || scanChannel != null) {
            return
        }

        val provider = channelProvider
        if (provider == null) {
            scheduleRetry()
            return
        }

        runCatching {
            val capabilities = Capabilities()
            val assignment = ExtendedAssignment()
            capabilities.supportBackgroundScanning(true)
            assignment.enableBackgroundScanning()

            val channel = provider.acquireChannel(appContext, PredefinedNetwork.ANT_PLUS, capabilities)
            channel.setChannelEventHandler(channelEventHandler)
            channel.assign(ChannelType.SLAVE_RECEIVE_ONLY, assignment)
            channel.setRfFrequency(RF_FREQUENCY_ANT_PLUS)
            channel.setSearchPriority(11)
            channel.setAdapterWideLibConfig(LIB_CONFIG)
            channel.setChannelId(ChannelId(0, 0, 0))
            channel.open()
            scanChannel = channel
            line("ANT+ background scan opened on RF=$RF_FREQUENCY_ANT_PLUS")
        }.onFailure { throwable ->
            line("Unable to open ANT+ scan channel: ${throwable.message}")
            closeScanChannel()
            scheduleRetry()
        }
    }

    private val channelEventHandler = object : IAntChannelEventHandler {
        override fun onReceiveMessage(
            messageFromAntType: MessageFromAntType?,
            antMessageParcel: AntMessageParcel?,
        ) {
            if (messageFromAntType == null || antMessageParcel == null) {
                return
            }
            line(decodeMessage(messageFromAntType, antMessageParcel))
        }

        override fun onChannelDeath() {
            line("ANT scan channel died")
            closeScanChannel()
            scheduleRetry()
        }
    }

    private fun decodeMessage(
        messageType: MessageFromAntType,
        parcel: AntMessageParcel,
    ): String = when (messageType) {
        MessageFromAntType.BROADCAST_DATA -> decodeBroadcast(parcel)
        MessageFromAntType.ACKNOWLEDGED_DATA -> decodeAcknowledged(parcel)
        MessageFromAntType.CHANNEL_EVENT -> {
            val event = ChannelEventMessage(parcel)
            "EVENT code=${event.getEventCode()} raw=$event"
        }
        MessageFromAntType.CHANNEL_RESPONSE -> {
            val response = ChannelResponseMessage(parcel)
            "RESPONSE code=${response.getResponseCode()} raw=$response"
        }
        MessageFromAntType.CHANNEL_STATUS -> {
            val status = ChannelStatusMessage(parcel)
            "STATUS raw=$status"
        }
        else -> "$messageType raw=$parcel"
    }

    private fun decodeBroadcast(parcel: AntMessageParcel): String {
        val message = BroadcastDataMessage(parcel)
        val payload = message.getPayload()
        val extendedData = if (message.hasExtendedData()) message.getExtendedData() else null
        return formatDataFrame("BROADCAST", payload, extendedData)
    }

    private fun decodeAcknowledged(parcel: AntMessageParcel): String {
        val message = AcknowledgedDataMessage(parcel)
        return formatDataFrame("ACK", message.getPayload(), null)
    }

    private fun formatDataFrame(kind: String, payload: ByteArray, extendedData: ExtendedData?): String {
        val channelId = extendedData?.getChannelId()
        val deviceKey = channelId?.toDeviceKey() ?: "unknown"
        val rssi = if (extendedData?.hasRssi() == true) {
            " rssi=${extendedData.getRssi().getRssiValue()}"
        } else {
            ""
        }
        return "$kind device=$deviceKey payload=${payload.toHex()}$rssi${describeChanges(deviceKey, payload)}"
    }

    private fun describeChanges(deviceKey: String, payload: ByteArray): String {
        val previous = previousPayloadByDevice.put(deviceKey, payload.copyOf()) ?: return " first"
        val changes = payload.indices.mapNotNull { index ->
            val old = if (index < previous.size) previous[index] else null
            val new = payload[index]
            if (old == new) {
                null
            } else {
                "$index:${old?.toHexByte() ?: "--"}>${new.toHexByte()}"
            }
        }
        if (changes.isEmpty()) {
            return " changed=[]"
        }
        return " changed=[${changes.joinToString(",")}]"
    }

    private fun closeScanChannel() {
        val channel = scanChannel ?: return
        scanChannel = null
        runCatching { channel.close() }
        runCatching { channel.clearAdapterEventHandler() }
        runCatching { channel.clearChannelEventHandler() }
        runCatching { channel.release() }
    }

    private fun registerProviderReceiver() {
        if (receiverRegistered) {
            return
        }
        val filter = IntentFilter(AntChannelProvider.ACTION_CHANNEL_PROVIDER_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(providerStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(providerStateReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterProviderReceiver() {
        if (!receiverRegistered) {
            return
        }
        runCatching { appContext.unregisterReceiver(providerStateReceiver) }
        receiverRegistered = false
    }

    private fun line(line: String) {
        handler.post { listener.onSnifferLine(line) }
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { byte -> byte.toHexByte() }

    private fun Byte.toHexByte(): String =
        String.format(Locale.US, "%02X", toInt() and 0xFF)

    private fun ChannelId.toDeviceKey(): String =
        "num=${getDeviceNumber()} type=${getDeviceType()} tx=${getTransmissionType()}"
}
