package com.lenne0815.magicshinesniffer

class FrameAnalyzer {
    private var previousB4Payload: ByteArray? = null

    @Synchronized
    fun analyze(frame: ByteArray): String {
        val hex = frame.toHex()
        if (frame.size < 5) return "RX $hex | invalid: too short"

        val declaredLength = frame[1].uInt()
        val command = frame[2].uInt()
        val checksumIndex = frame.lastIndex - 1
        val expectedChecksum = frame[checksumIndex].uInt()
        val calculatedChecksum = (1 until checksumIndex).fold(0) { value, index ->
            value xor frame[index].uInt()
        }
        val basics = buildString {
            append("RX $hex")
            append(" | bytes=${frame.size} declared=$declaredLength")
            append(" lengthOk=${declaredLength == frame.size}")
            append(" endOk=${frame.last().uInt() == 0xED}")
            append(" cmd=%02X".format(command))
            append(" checksum=%02X/%02X".format(expectedChecksum, calculatedChecksum))
            append(" checksumOk=${expectedChecksum == calculatedChecksum}")
        }
        if (command == 0xBA && checksumIndex >= 8) {
            val content = frame.copyOfRange(4, checksumIndex)
            return "$basics | BA runtime=${content[0].uInt()}h ${content[1].uInt()}m brightness=${content[3].uInt()}% content=${content.toHex()}"
        }
        if (command != 0xB4 || checksumIndex <= 3) return basics

        val payload = frame.copyOfRange(3, checksumIndex)
        val indexedPayload = payload.mapIndexed { index, value -> "$index=${value.toHexByte()}" }
        val changes = previousB4Payload?.let { previous ->
            payload.indices.mapNotNull { index ->
                val old = previous.getOrNull(index)
                val new = payload[index]
                if (old == new) null else "$index:${old?.toHexByte() ?: "--"}>${new.toHexByte()}"
            }
        } ?: emptyList()
        previousB4Payload = payload.copyOf()
        return "$basics | B4 payload=[${indexedPayload.joinToString(",")}] changed=[${changes.joinToString(",")}]"
    }

    private fun ByteArray.toHex(): String = joinToString("") { it.toHexByte() }

    private fun Byte.toHexByte(): String = "%02X".format(uInt())

    private fun Byte.uInt(): Int = toInt() and 0xFF
}
