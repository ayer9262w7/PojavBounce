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

/**
 * HumanHybridAngleSmooth
 *
 * Hybrid aiming that aims to be human-like (reaction delay, micro-tremor, EMA filter,
 * spring-like smoothing blended with acceleration fallback).
 *
 * Không dùng motionX/motionZ/posX/... để tránh mismatch mapping,
 * chỉ dựa trên RotationDelta + entity distance (đã có trong repo).
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

    // EMA internal state (keeps filtered predicted rotation)
    private var emaYaw: Float? = null
    private var emaPitch: Float? = null
    private var emaCount = 0
    private val emaInitWindow = 3
    private val emaAlpha = 0.28f // fixed internal default

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

        // Fix compile: use explicit if/else for checkDistance to avoid overload ambiguity
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

        // Avoid kotlin.math.max overload ambiguity by using explicit comparison
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
        // prediction offset from rotation delta
        val predYawOffset = diff.deltaYaw * predictionStrength
        val predPitchOffset = diff.deltaPitch * predictionStrength

        // dynamic/adaptive bases
        val aYawBase = if (dynamic.enabled && crosshair) dynamic.crosshairBoost else baseYawAccel
        val aPitchBase = basePitchAccel

        val distCoef = (dynamic.distanceCoef * distance).toFloat()

        // randomness ranges
        val yawRange = (-aYawBase.random() + distCoef)..(aYawBase.random() + distCoef)
        val pitchRange = (-aPitchBase.random() + distCoef)..(aPitchBase.random() + distCoef)

        // acceleration-like delta
        val yawAccel = RotationUtil.angleDifference(diff.deltaYaw, prevDiff.deltaYaw).coerceIn(yawRange)
        val pitchAccel = RotationUtil.angleDifference(diff.deltaPitch, prevDiff.deltaPitch).coerceIn(pitchRange)

        // human-like jitter
        val jitterYaw = ((sin(System.nanoTime() * 1e-9 * 3.0) * 0.5) * humanJitter).toFloat()
        val jitterPitch = ((cos(System.nanoTime() * 1e-9 * 2.7) * 0.4) * humanJitter).toFloat()

        // final step
        var finalYaw = prevDiff.deltaYaw + yawAccel + predYawOffset + jitterYaw
        var finalPitch = prevDiff.deltaPitch + pitchAccel + predPitchOffset + jitterPitch

        // clamp per-tick
        val ms = maxStep
        finalYaw = finalYaw.coerceIn(-ms..ms)
        finalPitch = finalPitch.coerceIn(-ms..ms)

        // normalize/wrap angles
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
