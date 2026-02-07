package com.fm.digital.ui

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
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

    fun d(message: String) = log("D", message, Color.WHITE)
    fun i(message: String) = log("I", message, Color.GREEN)
    fun w(message: String) = log("W", message, Color.YELLOW)

    fun e(message: String, throwable: Throwable? = null) {
        log("E", message, Color.RED)
        throwable?.let {
            Log.e(TAG, message, it)
            log("E", it.stackTraceToString(), Color.RED)
        }
    }

    fun log(message: String) = i(message)

    private fun log(level: String, message: String, color: Int) {
        val timestamp = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Locale.getDefault()
        ).format(Date())

        val logMessage = "$timestamp $level: $message"

        when (level) {
            "D" -> Log.d(TAG, logMessage)
            "I" -> Log.i(TAG, logMessage)
            "W" -> Log.w(TAG, logMessage)
            "E" -> Log.e(TAG, logMessage)
        }

        logWriter?.let {
            try {
                it.append(logMessage)
                it.append("\n")
                it.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to log file", e)
            }
        }

        logTextView?.post {
            val spannable = SpannableString("$logMessage\n")
            spannable.setSpan(
                ForegroundColorSpan(color),
                0,
                spannable.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            logTextView?.append(spannable)
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
