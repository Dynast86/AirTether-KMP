package com.dynast.airtether.core.nikonptp.pacing

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

/**
 * Standard cinema & broadcast camera recording frame rates with exact nanosecond frame intervals.
 */
enum class TargetFrameRate(
    val fps: Float,
    val intervalNanos: Long,
    val displayFpsLabel: String,
    val nikonHeaderCode: Int
) {
    FPS_23_976(
        fps = 23.976f,
        intervalNanos = 41_708_375L, // ~41.71 ms
        displayFpsLabel = "23.98",
        nikonHeaderCode = 0
    ),
    FPS_25(
        fps = 25.0f,
        intervalNanos = 40_000_000L, // 40.00 ms
        displayFpsLabel = "25.00",
        nikonHeaderCode = 1
    ),
    FPS_29_97(
        fps = 29.97f,
        intervalNanos = 33_366_700L, // ~33.37 ms
        displayFpsLabel = "29.97",
        nikonHeaderCode = 2
    ),
    FPS_59_94(
        fps = 59.94f,
        intervalNanos = 16_683_350L, // ~16.68 ms
        displayFpsLabel = "59.94",
        nikonHeaderCode = 3
    );

    /**
     * Returns the effective target interval in nanoseconds, optionally capping `59.94fps` to `29.97fps`
     * when USB/PTP bus bandwidth is constrained.
     */
    fun effectiveIntervalNanos(capHighFpsForBandwidth: Boolean = false): Long {
        return if (this == FPS_59_94 && capHighFpsForBandwidth) {
            FPS_29_97.intervalNanos
        } else {
            intervalNanos
        }
    }

    companion object {
        fun fromNikonCode(code: Int): TargetFrameRate = when (code) {
            0 -> FPS_23_976
            1 -> FPS_25
            2 -> FPS_29_97
            3 -> FPS_59_94
            else -> FPS_23_976
        }
    }
}

/**
 * Frame pacing synchronization mode selectable from the Director Top HUD.
 * - [AUTO_SYNC]: Automatically follows the camera's configured recording frame rate (`23.976` / `25` / `29.97` / `59.94`).
 * - [MANUAL_24P]: Locks polling and display refresh rate to `23.976fps`.
 * - [MANUAL_30P]: Locks polling and display refresh rate to `29.97fps`.
 * - [MANUAL_MAX]: Unlocks `59.94fps` high-speed polling.
 */
enum class FramePacingMode(val badgeSuffix: String) {
    AUTO_SYNC("Sync"),
    MANUAL_24P("24p"),
    MANUAL_30P("30p"),
    MANUAL_MAX("Max");

    fun resolveTargetFrameRate(cameraConfiguredRate: TargetFrameRate): TargetFrameRate = when (this) {
        AUTO_SYNC -> cameraConfiguredRate
        MANUAL_24P -> TargetFrameRate.FPS_23_976
        MANUAL_30P -> TargetFrameRate.FPS_29_97
        MANUAL_MAX -> TargetFrameRate.FPS_59_94
    }

    fun next(): FramePacingMode = when (this) {
        AUTO_SYNC -> MANUAL_24P
        MANUAL_24P -> MANUAL_30P
        MANUAL_30P -> MANUAL_MAX
        MANUAL_MAX -> AUTO_SYNC
    }
}

/**
 * Snapshot of current adaptive frame pacing state for telemetry and HUD presentation.
 */
data class FramePacingSnapshot(
    val pacingMode: FramePacingMode = FramePacingMode.AUTO_SYNC,
    val cameraConfiguredRate: TargetFrameRate = TargetFrameRate.FPS_23_976,
    val effectiveTargetRate: TargetFrameRate = TargetFrameRate.FPS_23_976,
    val measuredFps: Float = 23.98f,
    val lastTransactionElapsedNanos: Long = 0L,
    val lastRemainingDelayNanos: Long = TargetFrameRate.FPS_23_976.intervalNanos
) {
    val formattedHudBadge: String
        get() = "${effectiveTargetRate.displayFpsLabel} FPS (${pacingMode.badgeSuffix})"
}

/**
 * High-precision monotonic frame pacer (`TimeSource.Monotonic`) that measures actual USB/TCP
 * `GetLiveViewImage` (`0x9203`) transaction + header parse duration (`elapsedNanos`) and delays
 * strictly for `remainingDelay = targetIntervalNanos - elapsedNanos`.
 */
class FramePacer(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    var cap5994ForBandwidth: Boolean = false
) {
    var lastElapsedNanos: Long = 0L
        private set

    var lastRemainingDelayNanos: Long = 0L
        private set

    var measuredFps: Float = TargetFrameRate.FPS_23_976.fps
        private set

    /**
     * Pure calculation of remaining sleep duration in nanoseconds given [targetIntervalNanos] and [elapsedNanos].
     */
    fun computeRemainingDelayNanos(
        targetIntervalNanos: Long,
        elapsedNanos: Long
    ): Long {
        return (targetIntervalNanos - elapsedNanos.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    /**
     * Executes [transactionBlock], measures the monotonic elapsed nanoseconds, sleeps for the exact
     * remaining frame budget (`targetIntervalNanos - elapsedNanos`), and updates [measuredFps].
     */
    suspend fun <T> executePacedFrame(
        targetRate: TargetFrameRate,
        transactionBlock: suspend () -> T
    ): T {
        val targetIntervalNanos = targetRate.effectiveIntervalNanos(cap5994ForBandwidth)
        val startMark = timeSource.markNow()

        val result = transactionBlock()

        val elapsedNanos = startMark.elapsedNow().inWholeNanoseconds
        val remainingNanos = computeRemainingDelayNanos(
            targetIntervalNanos = targetIntervalNanos,
            elapsedNanos = elapsedNanos
        )

        lastElapsedNanos = elapsedNanos
        lastRemainingDelayNanos = remainingNanos

        if (remainingNanos > 0L) {
            delay(remainingNanos.nanoseconds)
        }

        val totalCycleNanos = (elapsedNanos + remainingNanos).coerceAtLeast(1_000_000L)
        val instantFps = 1_000_000_000f / totalCycleNanos.toFloat()
        // Exponential moving average for stable HUD readout
        measuredFps = (measuredFps * 0.75f) + (instantFps * 0.25f)

        return result
    }
}
