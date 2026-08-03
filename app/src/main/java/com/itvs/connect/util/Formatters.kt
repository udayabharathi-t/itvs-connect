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

    /** Human duration focused on hours + minutes (as requested for ride details). */
    fun durationHoursMinutes(ms: Long): String {
        val totalMin = TimeUnit.MILLISECONDS.toMinutes(ms).coerceAtLeast(0)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "%dh %dm".format(h, m)
            h > 0 -> "%dh".format(h)
            else -> "%dm".format(m)
        }
    }

    fun km(value: Double): String = "%.1f km".format(value)
    fun kmL(value: Double?): String =
        if (value == null || value <= 0) "—" else "%.1f km/L".format(value)

    fun litres(value: Double?): String =
        if (value == null || value <= 0) "—" else "%.2f L".format(value)

    fun speed(value: Double): String = "%.0f km/h".format(value)

    fun latLng(lat: Double?, lng: Double?): String =
        if (lat == null || lng == null) "—" else "%.5f, %.5f".format(lat, lng)
}
