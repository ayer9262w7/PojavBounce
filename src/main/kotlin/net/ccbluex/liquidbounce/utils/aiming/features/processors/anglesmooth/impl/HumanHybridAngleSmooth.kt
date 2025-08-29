package net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl

import it.unimi.dsi.fastutil.floats.FloatFloatPair
import net.ccbluex.liquidbounce.config.types.nesting.ChoiceConfigurable
import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.data.RotationDelta
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.AngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.aiming.utils.facingEnemy
import net.ccbluex.liquidbounce.utils.entity.boxedDistanceTo
import net.ccbluex.liquidbounce.utils.entity.lastRotation
import net.ccbluex.liquidbounce.utils.kotlin.component1
import net.ccbluex.liquidbounce.utils.kotlin.component2
import kotlin.math.*
import kotlin.random.Random

/**
 * HumanHybridAngleSmooth
 *
 * Triệt để sửa các nguồn gây ambiguity kiểu (Number & Comparable<Nothing>) dẫn tới
 * lỗi compile Kotlin khi dùng các delegate config/range. Giải pháp:
 *  - Không gán hai delegate khác kiểu vào một biến chung.
 *  - Không pattern-match trực tiếp vào ClosedRange/ClosedFloatingPointRange (khi làm thế
 *    compiler vẫn có thể suy ra Comparable<Nothing>).
 *  - Trích xuất số từ delegate an toàn: nếu là Number thì dùng toFloat(); nếu không thì
 *    parse chuỗi (regex) để lấy 1 hoặc 2 số (trường hợp range như "14.0..22.0").
 *  - Không dùng toán tử .. trên giá trị không rõ kiểu; dùng coerceIn(min, max).
 *
 * Mục tiêu: compile sạch trên Actions + giữ nguyên hành vi (prediction, jitter, distCoef...)
 */
class HumanHybridAngleSmooth(parent: ChoiceConfigurable<*>) : AngleSmooth("HumanHybrid", parent) {

    // Base acceleration ranges (configurable)
    private val baseYawAccel by floatRange("BaseYawAccel", 14f..22f, 1f..180f)
    private val basePitchAccel by floatRange("BasePitchAccel", 12f..20f, 1f..180f)

    // Prediction and jitter
    private val predictionStrength by float("PredictionStrength", 0.25f, 0f..1f)
    private val humanJitter by float("HumanJitter", 0.6f, 0f..3f)
    private val maxStep by float("MaxStep", 10f, 0.1f..180f)

    // Dynamic sub-config (distance-based behavior)
    private inner class Dynamic : ToggleableConfigurable(this, "Dynamic", true) {
        val distanceCoef by float("DistanceCoef", -1.0f, -5f..5f)
        val crosshairBoost by floatRange("CrosshairBoost", 16f..22f, 1f..180f)
    }

    private val dynamic = tree(Dynamic())

    // EMA internal state (keeps filtered predicted rotation) — giữ lại cho tương lai
    private var emaYaw: Float? = null
    private var emaPitch: Float? = null
    private var emaCount = 0
    private val emaInitWindow = 3
    private val emaAlpha = 0.28f

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        val prevRotation = RotationManager.previousRotation ?: player.lastRotation
        val prevDiff = prevRotation.rotationDeltaTo(currentRotation)
        val diff = currentRotation.rotationDeltaTo(targetRotation)

        val entity = rotationTarget.entity
        val distance = entity?.let { player.boxedDistanceTo(it) } ?: 0.0

        val checkDistance = if (distance < 3.0) 3.0 else distance
        val crosshair = entity?.let { facingEnemy(it, checkDistance, currentRotation) } == true

        val (yawStep, pitchStep) = computeHybridStep(prevDiff, diff, entity, crosshair, distance)

        return Rotation(
            currentRotation.yaw + yawStep,
            currentRotation.pitch + pitchStep
        )
    }

    override fun calculateTicks(currentRotation: Rotation, targetRotation: Rotation): Int {
        val prevRotation = RotationManager.previousRotation ?: player.lastRotation
        val prevDiff = prevRotation.rotationDeltaTo(currentRotation)
        val diff = currentRotation.rotationDeltaTo(targetRotation)

        if (abs(diff.deltaYaw) < 1e-6f && abs(diff.deltaPitch) < 1e-6f) return 0

        val (yawStep, pitchStep) = computeHybridStep(prevDiff, diff, null, false, 0.0)
        if (abs(yawStep) < 1e-6f && abs(pitchStep) < 1e-6f) return 0

        val ticksH = floor(abs(diff.deltaYaw) / abs(yawStep)).let { if (it.isNaN()) 0.0 else it }
        val ticksV = floor(abs(diff.deltaPitch) / abs(pitchStep)).let { if (it.isNaN()) 0.0 else it }

        val ticksD = if (ticksH > ticksV) ticksH else ticksV
        return ticksD.toInt().coerceAtLeast(1)
    }

    @Suppress("LongParameterList")
    private fun computeHybridStep(
        prevDiff: RotationDelta,
        diff: RotationDelta,
        entity: net.minecraft.entity.Entity?,
        crosshair: Boolean,
        distance: Double
    ): FloatFloatPair {
        // prediction offset
        val predYawOffset = diff.deltaYaw * predictionStrength
        val predPitchOffset = diff.deltaPitch * predictionStrength

        val distCoef = (dynamic.distanceCoef * distance).toFloat()

        // Helper: parse numeric tokens from arbitrary toString() (handles "14.0..22.0", "16.0", etc.)
        fun parseNumbers(s: String?): List<Float> {
            if (s == null) return emptyList()
            val regex = """-?\d+(?:\.\d+)?""".toRegex()
            return regex.findAll(s).mapNotNull { it.value.toFloatOrNull() }.toList()
        }

        // Extract a float or a range from a config-backed value robustly without pattern-matching on range types.
        // If value is Number -> return Number as Float.
        // Else try parseNumbers on toString(). If it yields >=2 numbers, treat them as [lo, hi].
        // If yields 1 number -> that number (singleton).
        // Returns Pair(lo, hi) to represent available numeric interval.
        fun extractNumericInterval(value: Any?, fallbackLo: Float, fallbackHi: Float): Pair<Float, Float> {
            if (value is Number) {
                val v = value.toFloat()
                return Pair(v, v)
            }
            val nums = parseNumbers(value?.toString())
            return when {
                nums.size >= 2 -> {
                    // pick first two as bounds (some config string like "14.0..22.0" -> [14,22])
                    val lo = min(nums[0], nums[1])
                    val hi = max(nums[0], nums[1])
                    Pair(lo, hi)
                }
                nums.size == 1 -> Pair(nums[0], nums[0])
                else -> Pair(fallbackLo, fallbackHi)
            }
        }

        // Decide yaw/pitch base numeric intervals per-branch (avoid assigning different delegate types to one var)
        val baseYawFallbackLo = 14f
        val baseYawFallbackHi = 22f
        val basePitchFallbackLo = 12f
        val basePitchFallbackHi = 20f

        val yawInterval: Pair<Float, Float> = if (dynamic.enabled && crosshair) {
            extractNumericInterval(dynamic.crosshairBoost, baseYawFallbackLo, baseYawFallbackHi)
        } else {
            extractNumericInterval(baseYawAccel, baseYawFallbackLo, baseYawFallbackHi)
        }

        val pitchInterval: Pair<Float, Float> = extractNumericInterval(basePitchAccel, basePitchFallbackLo, basePitchFallbackHi)

        // Sample two independent random magnitudes from each interval (preserve randomness behavior)
        fun sampleFromInterval(lo: Float, hi: Float): Float = if (hi <= lo) lo else Random.nextFloat() * (hi - lo) + lo

        val yawRandA = sampleFromInterval(yawInterval.first, yawInterval.second)
        val yawRandB = sampleFromInterval(yawInterval.first, yawInterval.second)
        val pitchRandA = sampleFromInterval(pitchInterval.first, pitchInterval.second)
        val pitchRandB = sampleFromInterval(pitchInterval.first, pitchInterval.second)

        val yawMin = -yawRandA + distCoef
        val yawMax = yawRandB + distCoef
        val yawLo = min(yawMin, yawMax)
        val yawHi = max(yawMin, yawMax)

        val pitchMin = -pitchRandA + distCoef
        val pitchMax = pitchRandB + distCoef
        val pitchLo = min(pitchMin, pitchMax)
        val pitchHi = max(pitchMin, pitchMax)

        // angleDifference may return Double; force Float to keep types uniform
        val yawDiff = RotationUtil.angleDifference(diff.deltaYaw, prevDiff.deltaYaw).toFloat()
        val pitchDiff = RotationUtil.angleDifference(diff.deltaPitch, prevDiff.deltaPitch).toFloat()

        // Sample inner bound for acceleration (keeps randomness and uses numeric bounds)
        val yawAccelSampleLo = sampleFromInterval(yawLo, yawHi)
        val yawAccelSampleHi = sampleFromInterval(yawLo, yawHi)
        val pitchAccelSampleLo = sampleFromInterval(pitchLo, pitchHi)
        val pitchAccelSampleHi = sampleFromInterval(pitchLo, pitchHi)

        val yawAccel = yawDiff.coerceIn(min(yawAccelSampleLo, yawAccelSampleHi), max(yawAccelSampleLo, yawAccelSampleHi))
        val pitchAccel = pitchDiff.coerceIn(min(pitchAccelSampleLo, pitchAccelSampleHi), max(pitchAccelSampleLo, pitchAccelSampleHi))

        // human-like jitter
        val jitterYaw = ((sin(System.nanoTime() * 1e-9 * 3.0) * 0.5) * humanJitter).toFloat()
        val jitterPitch = ((cos(System.nanoTime() * 1e-9 * 2.7) * 0.4) * humanJitter).toFloat()

        // final step composition (all Float)
        var finalYaw = prevDiff.deltaYaw + yawAccel + predYawOffset + jitterYaw
        var finalPitch = prevDiff.deltaPitch + pitchAccel + predPitchOffset + jitterPitch

        // clamp per-tick using min/max coerceIn overload to avoid .. ambiguity
        val ms = maxStep
        finalYaw = finalYaw.coerceIn(-ms, ms)
        finalPitch = finalPitch.coerceIn(-ms, ms)

        // normalize/wrap angles and clamp pitch
        finalYaw = wrapAngle180(finalYaw)
        finalPitch = wrapAngle180(finalPitch).coerceIn(-90f, 90f)

        return FloatFloatPair.of(finalYaw, finalPitch)
    }

    private fun wrapAngle180(a: Float): Float {
        var x = a
        while (x <= -180f) x += 360f
        while (x > 180f) x -= 360f
        return x
    }
}