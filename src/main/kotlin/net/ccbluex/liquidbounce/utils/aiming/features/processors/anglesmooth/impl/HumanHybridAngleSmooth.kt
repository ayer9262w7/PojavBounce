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
import net.ccbluex.liquidbounce.utils.kotlin.random
import kotlin.math.*
import kotlin.random.Random

/**
 * HumanHybridAngleSmooth
 *
 * Fix: tránh gán các config-delegate khác kiểu vào cùng một biến; sample trực tiếp per-branch
 * để compiler không suy kiểu Comparable<Nothing>.
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

    // EMA internal state (keeps filtered predicted rotation) — giữ lại
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

        // helper: lấy numeric nếu là Number, hoặc cố parse toString() -> Float
        fun extractFloat(value: Any?, fallback: Float = 0f): Float {
            if (value is Number) return value.toFloat()
            return value?.toString()?.toFloatOrNull() ?: fallback
        }

        // --- Sample trực tiếp per-branch để tránh gán delegate khác kiểu vào cùng 1 var ---
        val yawRandA = if (dynamic.enabled && crosshair) {
            // dynamic.crosshairBoost có thể là delegate/range; extractFloat trả Float hoặc parse
            extractFloat(dynamic.crosshairBoost, 0f)
        } else {
            extractFloat(baseYawAccel, 0f)
        }
        val yawRandB = if (dynamic.enabled && crosshair) {
            extractFloat(dynamic.crosshairBoost, 0f)
        } else {
            extractFloat(baseYawAccel, 0f)
        }

        val pitchRandA = extractFloat(basePitchAccel, 0f)
        val pitchRandB = extractFloat(basePitchAccel, 0f)

        val yawMin = -yawRandA + distCoef
        val yawMax = yawRandB + distCoef
        val yawLo = min(yawMin, yawMax)
        val yawHi = max(yawMin, yawMax)

        val pitchMin = -pitchRandA + distCoef
        val pitchMax = pitchRandB + distCoef
        val pitchLo = min(pitchMin, pitchMax)
        val pitchHi = max(pitchMin, pitchMax)

        // angle difference -> ensure Float for coerceIn
        val yawDiff = RotationUtil.angleDifference(diff.deltaYaw, prevDiff.deltaYaw).toFloat()
        val pitchDiff = RotationUtil.angleDifference(diff.deltaPitch, prevDiff.deltaPitch).toFloat()

        // sample uniform in [lo, hi] by using kotlin.random (preserve randomness behavior)
        fun sampleIn(lo: Float, hi: Float): Float = if (hi <= lo) lo else Random.nextFloat() * (hi - lo) + lo

        val yawAccel = yawDiff.coerceIn(sampleIn(yawLo, yawHi), sampleIn(yawLo, yawHi)) // sample to generate accel bounds
        val pitchAccel = pitchDiff.coerceIn(sampleIn(pitchLo, pitchHi), sampleIn(pitchLo, pitchHi))

        // human-like jitter
        val jitterYaw = ((sin(System.nanoTime() * 1e-9 * 3.0) * 0.5) * humanJitter).toFloat()
        val jitterPitch = ((cos(System.nanoTime() * 1e-9 * 2.7) * 0.4) * humanJitter).toFloat()

        // final step composition
        var finalYaw = prevDiff.deltaYaw + yawAccel + predYawOffset + jitterYaw
        var finalPitch = prevDiff.deltaPitch + pitchAccel + predPitchOffset + jitterPitch

        // clamp per-tick using coerceIn(min, max) overload (no range operator ambiguity)
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