package com.lenne0815.karoosramsniffer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FrameRecorder(context: Context) {
    val file: File

    init {
        val directory = File(context.filesDir, "ant-sniffer")
        if (!directory.isDirectory) {
            directory.mkdirs()
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        file = File(directory, "sram-ant-sniffer-$stamp.txt")
        file.createNewFile()
    }

    @Synchronized
    fun append(line: String) {
        file.appendText(line + "\n")
    }
}
