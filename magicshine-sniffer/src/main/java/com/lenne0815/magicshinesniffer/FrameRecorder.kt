package com.lenne0815.magicshinesniffer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FrameRecorder(context: Context) {
    val file: File

    init {
        val directory = File(context.filesDir, "ble-sniffer").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        file = File(directory, "magicshine-ble-$stamp.txt").apply { createNewFile() }
    }

    @Synchronized
    fun append(line: String) {
        file.appendText(line + "\n")
    }
}
