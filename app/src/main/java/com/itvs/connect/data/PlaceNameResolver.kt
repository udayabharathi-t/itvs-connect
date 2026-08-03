package com.itvs.connect.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Reverse-geocodes lat/lng to a short place label using Android's system [Geocoder].
 *
 * No Google Cloud Console / Maps API key is required. On devices with Google Play
 * services the platform talks to Google's geocoder backend; elsewhere it may use
 * another provider or return null if unavailable / offline.
 */
data class PlaceParts(
    val thoroughfare: String? = null,
    val subThoroughfare: String? = null,
    val subLocality: String? = null,
    val locality: String? = null,
    val subAdminArea: String? = null,
    val adminArea: String? = null,
    val featureName: String? = null,
    val addressLine: String? = null
)

class PlaceNameResolver(context: Context) {
    private val appContext = context.applicationContext

    suspend fun resolve(lat: Double?, lng: Double?): String? {
        if (lat == null || lng == null) return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                if (!Geocoder.isPresent()) return@runCatching null
                val geocoder = Geocoder(appContext, Locale.getDefault())
                val address = firstAddress(geocoder, lat, lng) ?: return@runCatching null
                formatAddress(address)
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }

    private suspend fun firstAddress(geocoder: Geocoder, lat: Double, lng: Double): Address? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lng, 1) { list ->
                    if (cont.isActive) cont.resume(list.firstOrNull())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
        }
    }

    companion object {
        fun fromAddress(address: Address) = PlaceParts(
            thoroughfare = address.thoroughfare,
            subThoroughfare = address.subThoroughfare,
            subLocality = address.subLocality,
            locality = address.locality,
            subAdminArea = address.subAdminArea,
            adminArea = address.adminArea,
            featureName = address.featureName,
            addressLine = address.getAddressLine(0)
        )

        /** Build a compact human label from a geocoder [Address]. */
        fun formatAddress(address: Address): String = formatParts(fromAddress(address))

        fun formatParts(parts: PlaceParts): String {
            val street = listOfNotNull(
                parts.thoroughfare,
                parts.subThoroughfare
            ).joinToString(" ").trim().ifBlank { null }

            val area = sequenceOf(
                parts.subLocality,
                parts.locality,
                parts.subAdminArea,
                parts.adminArea
            ).mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .distinct()
                .take(2)
                .toList()

            val composed = when {
                street != null && area.isNotEmpty() -> "$street, ${area.joinToString(", ")}"
                street != null -> street
                area.isNotEmpty() -> area.joinToString(", ")
                !parts.featureName.isNullOrBlank() &&
                    parts.featureName != street -> parts.featureName.trim()
                else -> parts.addressLine?.trim().orEmpty()
            }
            return shorten(composed)
        }

        fun shorten(raw: String, maxLen: Int = 56): String {
            val cleaned = raw
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimEnd(',')
            if (cleaned.length <= maxLen) return cleaned
            return cleaned.take(maxLen - 1).trimEnd(',', ' ', '.') + "…"
        }

        /**
         * Title shown in lists / headers.
         * Custom [RideEntity.label] wins; otherwise "Start → End" from place names.
         */
        fun displayTitle(ride: RideEntity): String {
            if (ride.label.isNotBlank()) return ride.label.trim()
            val start = ride.startPlaceName?.trim().orEmpty()
            val end = ride.endPlaceName?.trim().orEmpty()
            return when {
                start.isNotEmpty() && end.isNotEmpty() &&
                    start.equals(end, ignoreCase = true) -> start
                start.isNotEmpty() && end.isNotEmpty() -> "$start → $end"
                start.isNotEmpty() -> "From $start"
                end.isNotEmpty() -> "To $end"
                else -> ""
            }
        }
    }
}
