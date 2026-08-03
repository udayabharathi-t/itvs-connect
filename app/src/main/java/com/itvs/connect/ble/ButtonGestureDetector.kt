package com.itvs.connect.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ButtonGestureDetector(
    private val scope: CoroutineScope,
    private var doublePressWindowMs: Int = 1500,
    private var longPressThresholdMs: Int = 1000,
    private var cooldownMs: Int = 500,
    private val onGesture: (tapCount: Int, isLong: Boolean) -> Unit,
    private val onContinuous: ((ButtonAction) -> Unit)? = null,
    private val continuousActionProvider: (() -> ButtonAction?)? = null
) {
    private val tapCount = AtomicInteger(0)
    private val isLong = AtomicBoolean(false)
    private var windowJob: Job? = null
    private var longJob: Job? = null
    private var continuousJob: Job? = null
    private var lastActionAt = 0L
    private var continuousRunning = false

    fun updateTiming(doubleWindow: Int, longThreshold: Int, cooldown: Int) {
        doublePressWindowMs = doubleWindow
        longPressThresholdMs = longThreshold
        cooldownMs = cooldown
    }

    fun onHold() {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < cooldownMs) return
        val count = tapCount.incrementAndGet().coerceAtMost(4)
        isLong.set(false)
        continuousRunning = false
        continuousJob?.cancel()
        windowJob?.cancel()
        longJob?.cancel()
        longJob = scope.launch {
            delay(longPressThresholdMs.toLong())
            isLong.set(true)
            val action = continuousActionProvider?.invoke()
            if (action == ButtonAction.VOLUME_UP || action == ButtonAction.VOLUME_DOWN) {
                continuousRunning = true
                continuousJob = scope.launch {
                    while (true) {
                        onContinuous?.invoke(action)
                        delay(500)
                    }
                }
            }
        }
        // Keep count for release coalescing; unused warning silence:
        @Suppress("UNUSED_VARIABLE")
        val ignored = count
    }

    fun onRelease() {
        longJob?.cancel()
        continuousJob?.cancel()
        if (continuousRunning) {
            continuousRunning = false
            tapCount.set(0)
            isLong.set(false)
            lastActionAt = System.currentTimeMillis()
            return
        }
        windowJob?.cancel()
        windowJob = scope.launch {
            delay(doublePressWindowMs.toLong())
            val finalCount = tapCount.getAndSet(0)
            val finalLong = isLong.getAndSet(false)
            if (finalCount > 0) {
                lastActionAt = System.currentTimeMillis()
                onGesture(finalCount, finalLong)
            }
        }
    }
}
