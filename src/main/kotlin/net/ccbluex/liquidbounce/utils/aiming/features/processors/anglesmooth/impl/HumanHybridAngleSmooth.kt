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
 * Cơ chế hybrid: kết hợp acceleration-based smoothing + small human-like jitter +
 * simple kinematic prediction (dùng pos - prevPos để compatible với nhiều mapping).
 *
 * Thiết kế theo mẫu của các AngleSmooth khác trong repo (vd. AccelerationAngleSmooth).
 */
class HumanHybridAngleSmooth(parent: ChoiceConfigurable<*>) : AngleSmooth("HumanHybrid", parent) {

    private val baseYawAccel by floatRange("BaseYawAccel", 14f..22f, 1f..180f)
    private val basePitchAccel by floatRange("BasePitchAccel", 12f..20f, 1f..180f)

    private val predictionStrength by float("PredictionStrength", 0.25f, 0f..1f)
    private val humanJitter by float("HumanJitter", 0.6f, 0f..3f)
    private val maxStep by float("MaxStep", 10f, 0.1f..180f)

    private inner class Dynamic : ToggleableConfigurable(this, "Dynamic", true) {
        val distanceCoef by float("DistanceCoef", -1.0f, -5f..5f)
        val crosshairBoost by floatRange("CrosshairBoost", 16f..22f, 1f..180f)
    }

    private val dynamic = tree(Dynamic())

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
        val crosshair = entity?.let { facingEnemy(it, max(3.0, distance), currentRotation) } == true

        // compute hybrid yaw/pitch step
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

        if (abs(diff.deltaYaw) < 0.001f && abs(diff.deltaPitch) < 0.001f) return 0

        val (yawStep, pitchStep) = computeHybridStep(prevDiff, diff, null, false, 0.0)
        if (abs(yawStep) < 1e-6f || abs(pitchStep) < 1e-6f) return 0

        val ticksH = floor(abs(diff.deltaYaw) / abs(yawStep)).let { if (it.isNaN()) 0.0 else it }
        val ticksV = floor(abs(diff.deltaPitch) / abs(pitchStep)).let { if (it.isNaN()) 0.0 else it }

        return max(ticksH, ticksV).toInt().coerceAtLeast(1)
    }

    @Suppress("LongParameterList")
    private fun computeHybridStep(
        prevDiff: RotationDelta,
        diff: RotationDelta,
        entity: net.minecraft.entity.Entity?,
        crosshair: Boolean,
        distance: Double
    ): FloatFloatPair {
        // prediction from simple kinematics (pos - prevPos)
        val (predYawOffset, predPitchOffset) = entity?.let {
            val vx = (it.posX - it.prevPosX).toFloat()
            val vz = (it.posZ - it.prevPosZ).toFloat()
            val speed = sqrt((vx * vx + vz * vz).toDouble()).toFloat()
            // lead angle roughly proportional to movement direction & speed
            val heading = atan2(vz.toDouble(), vx.toDouble()).toFloat() * (180f / PI.toFloat())
            Pair((heading * 0.5f) * predictionStrength, 0f)
        } ?: Pair(0f, 0f)

        // base acceleration (adaptive)
        val aYawBase = if (dynamic.enabled && crosshair) dynamic.crosshairBoost else baseYawAccel
        val aPitchBase = basePitchAccel

        val distCoef = (dynamic.distanceCoef * distance).toFloat()

        // acceleration ranges with a little randomness
        val yawRange = (-aYawBase.random() + distCoef)..(aYawBase.random() + distCoef)
        val pitchRange = (-aPitchBase.random() + distCoef)..(aPitchBase.random() + distCoef)

        // compute acceleration-like delta
        val yawAccel = RotationUtil.angleDifference(diff.deltaYaw, prevDiff.deltaYaw)
            .coerceIn(yawRange) * 1.0f
        val pitchAccel = RotationUtil.angleDifference(diff.deltaPitch, prevDiff.deltaPitch)
            .coerceIn(pitchRange) * 1.0f

        // apply prediction offsets (small)
        val yawFromPrediction = predYawOffset
        val pitchFromPrediction = predPitchOffset

        // add human-like jitter scaled by humanJitter config
        val jitterYaw = ((sin(System.nanoTime().toDouble() * 1e-9 * 3.0) * 0.5) * humanJitter).toFloat()
        val jitterPitch = ((cos(System.nanoTime().toDouble() * 1e-9 * 2.7) * 0.4) * humanJitter).toFloat()

        // final step (prevDiff + accel + prediction + jitter)
        var finalYaw = prevDiff.deltaYaw + yawAccel + yawFromPrediction + jitterYaw
        var finalPitch = prevDiff.deltaPitch + pitchAccel + pitchFromPrediction + jitterPitch

        // clamp to reasonable per-tick step
        finalYaw = finalYaw.coerceIn(-maxStep.value, maxStep.value)
        finalPitch = finalPitch.coerceIn(-maxStep.value, maxStep.value)

        // ensure wrapped like human would (between -180..180)
        finalYaw = wrapAngleTo180(finalYaw)
        finalPitch = wrapAngleTo180(finalPitch).coerceIn(-90f, 90f)

        return FloatFloatPair.of(finalYaw, finalPitch)
    }

    // local helper to avoid depending on a particular MathHelper mapping
    private fun wrapAngleTo180(angle: Float): Float {
        var a = angle % 360f
        if (a <= -180f) a += 360f
        if (a > 180f) a -= 360f
        return a
    }
}
