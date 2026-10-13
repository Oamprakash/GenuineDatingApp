package com.genuine.app.data

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import kotlin.math.round

/** Current location rounded to 2 decimals (~1 km) before it ever leaves the phone. */
@SuppressLint("MissingPermission")
suspend fun coarseLocation(context: Context): Pair<Double, Double> {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val loc = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
        ?: client.lastLocation.await()
        ?: error("No location fix yet")
    return (round(loc.latitude * 100) / 100) to (round(loc.longitude * 100) / 100)
}