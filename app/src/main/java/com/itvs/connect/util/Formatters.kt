package com.itvs.connect.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Formatters {
    private val dateTime = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault())
    private val timeOnly = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun dateTime(ms: Long): String = dateTime.format(Date(ms))
    fun time(ms: Long): String = timeOnly.format(Date(ms))

    fun duration(ms: Long): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun km(value: Double): String = "%.1f km".format(value)
    fun kmL(value: Double?): String =
        if (value == null || value <= 0) "—" else "%.1f km/L".format(value)

    fun speed(value: Double): String = "%.0f km/h".format(value)
}
