package com.fm.digital.ui

import android.content.Context
import android.util.Log
import android.widget.TextView
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {

    private const val TAG = "FMDigitalLogger"
    private var logFile: File? = null
    private var logWriter: FileWriter? = null
    private var logTextView: TextView? = null

    fun initialize(context: Context, textView: TextView) {
        logTextView = textView
        try {
            val logDir = context.filesDir
            logFile = File(logDir, "app_log.txt")
            logWriter = FileWriter(logFile, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize file logger", e)
        }
    }

    fun log(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logMessage = "$timestamp: $message"

        // Log to logcat
        Log.d(TAG, logMessage)

        // Log to file
        logWriter?.apply {
            try {
                append(logMessage)
                append("\n")
                flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to log file", e)
            }
        }

        // Log to UI
        logTextView?.apply {
            post {
                append("$logMessage\n")
            }
        }
    }

    fun close() {
        try {
            logWriter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close log writer", e)
        }
    }
}