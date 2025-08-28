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
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.minecraft.util.math.MathHelper
import kotlin.math.*

/**
 * HumanHybridAngleSmooth
 *
 * - Kết hợp:
 *   * Reaction delay (giống phản xạ người)
 *   * Prediction (linear + entity motion)
 *   * EMA filter (làm mượt mục tiêu dự đoán)
 *   * Spring-damper smoothing (natural movement)
 *   * Acceleration fallback (dựa trên AccelerationAngleSmooth style)
 *   * Micro-adjustments / tremor (nhỏ, configurable)
 *
 * - Mục tiêu: cảm giác giống người nhất mà vẫn tracking tốt.
 */
class HumanHybridAngleSmooth(parent: ChoiceConfigurable<*>) : AngleSmooth("HumanHybrid", parent) {

    // === Core tunables ===
    private val baseYawAccel by floatRange("BaseYawAccel", 12f..20f, 1f..180f)
    private val basePitchAccel by floatRange("BasePitchAccel", 12f..20f, 1f..180f)

    // Reaction: introduce a short human reaction delay before starting large moves
    private inner class Reaction : ToggleableConfigurable(this, "Reaction", true) {
        val delayMs by int("DelayMs", 80, 0..400) // typical 80 - 250 ms human reaction
        val jitterMs by int("JitterMs", 20, 0..150) // random jitter added to reaction
    }

    // Prediction: linear extrapolation + optional entity motion lead
    private inner class Prediction : ToggleableConfigurable(this, "Prediction", true) {
        val factor by float("Factor", 0.28f, 0f..1f)
        val motionFactor by float("MotionFactor", 0.12f, 0f..1f)
        val useEntityMotion by boolean("UseEntityMotion", true)
    }

    // EMA filter to smooth predicted positions
    private inner class EMA : ToggleableConfigurable(this, "EMAFilter", true) {
        val alpha by float("Alpha", 0.28f, 0.01f..0.9f) // larger alpha => quicker response
        val initWindow by int("InitWindow", 3, 1..10)
    }

    // Spring-damper for natural movement
    private inner class Spring : ToggleableConfigurable(this, "SpringDamper", true) {
        val stiffness by float("Stiffness", 14f, 0.1f..100f)
        val damping by float("Damping", 5f, 0.0f..50f)
        val maxStep by float("MaxStep", 10f, 0.1f..180f)
    }

    // Sigmoid deceleration when approaching target (prevents overshoot)
    private inner class SigmoidDecel : ToggleableConfigurable(this, "SigmoidDecel", true) {
        val steepness by float("Steepness", 10f, 0.0f..20f)
        val midpoint by float("Midpoint", 0.35f, 0.0f..1.0f)
    }

    // Micro adjustments (tremor / micro-corrections)
    private inner class Micro : ToggleableConfigurable(this, "MicroAdjust", true) {
        val yawAmp by float("YawAmp", 0.012f, 0f..0.5f)
        val pitchAmp by float("PitchAmp", 0.010f, 0f..0.5f)
        val freq by float("Freq", 1.4f, 0.1f..5f)
    }

    private val reaction = tree(Reaction())
    private val prediction = tree(Prediction())
    private val ema = tree(EMA())
    private val spring = tree(Spring())
    private val sigmoid = tree(SigmoidDecel())
    private val micro = tree(Micro())

    // EMA state
    private var emaYaw: Float? = null
    private var emaPitch: Float? = null
    private var emaCount = 0

    // Spring velocity state
    private var velYaw = 0f
    private var velPitch = 0f

    // Reaction timer (ticks-based)
    private var lastTargetSeenAt: Long = 0L
    private var waitingUntil: Long = 0L

    init {
        lastTargetSeenAt = System.currentTimeMillis()
        waitingUntil = 0L
    }

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        // --- setup / context ---
        val prevRotation = RotationManager.previousRotation ?: player.lastRotation
        val prevDiff = prevRotation.rotationDeltaTo(currentRotation)
        val diff = currentRotation.rotationDeltaTo(targetRotation)

        val entity = rotationTarget.entity
        val distance = entity?.let { player.boxedDistanceTo(it) } ?: 0.0
        val onCrosshair = entity?.let { facingEnemy(it, max(3.0, distance), currentRotation) } == true

        // Update reaction timer: if target changed significantly, start reaction delay
        val now = System.currentTimeMillis()
        val significantChange = diff.length() > 2.0f || RotationUtil.angleDifference(diff.deltaYaw, prevDiff.deltaYaw) > 0.5f
        if (significantChange) {
            lastTargetSeenAt = now
            val jitter = if (reaction.jitterMs > 0) (Math.random() * reaction.jitterMs).toLong() else 0L
            waitingUntil = now + reaction.delayMs + jitter
        }

        // If still in reaction delay, do micro tremor only
        if (reaction.enabled && now < waitingUntil) {
            return microOnly(currentRotation)
        }

        // --- Prediction ---
        val predicted = if (prediction.enabled) {
            val predYaw = targetRotation.yaw + diff.deltaYaw * prediction.factor
            val predPitch = targetRotation.pitch + diff.deltaPitch * prediction.factor

            var outYaw = predYaw
            var outPitch = predPitch

            if (prediction.useEntityMotion && entity != null) {
                val vx = entity.motionX.toFloat()
                val vz = entity.motionZ.toFloat()
                val speed = sqrt(vx * vx + vz * vz)
                val extraFactor = (speed * prediction.motionFactor).coerceAtMost(0.8f)
                // lead in direction of motion (approx using atan2)
                val motionYaw = atan2(vz.toDouble(), vx.toDouble()).toFloat() * (180f / Math.PI.toFloat())
                outYaw = lerpAngle(outYaw, motionYaw, extraFactor)
            }
            Rotation(outYaw, outPitch)
        } else {
            targetRotation
        }

        // --- EMA filter ---
        val filtered = applyEma(predicted)

        // --- blended behavior: decide aggressive vs smooth --
        // Weight control: if on crosshair or diff small => be more aggressive
        val diffLen = diff.length()
        val aggressiveWeight = when {
            onCrosshair -> 0.9f
            diffLen < 6f -> 0.7f
            diffLen < 15f -> 0.45f
            else -> 0.25f
        }

        // --- Spring-Damper smoothing ---
        val desired = filtered
        val errYaw = RotationUtil.angleDifference(desired.yaw, currentRotation.yaw)
        val errPitch = RotationUtil.angleDifference(desired.pitch, currentRotation.pitch)

        val stiffness = spring.stiffness
        val damping = spring.damping

        val accelYaw = (stiffness * errYaw) - (damping * velYaw)
        val accelPitch = (stiffness * errPitch) - (damping * velPitch)

        velYaw += accelYaw * 1f // dt = 1 tick
        velPitch += accelPitch * 1f

        var springStepYaw = velYaw * 1f
        var springStepPitch = velPitch * 1f

        // clamp spring steps
        springStepYaw = springStepYaw.coerceIn(-spring.maxStep..spring.maxStep)
        springStepPitch = springStepPitch.coerceIn(-90f..90f)

        // --- Acceleration fallback (for strong tracking when needed) ---
        val (accYaw, accPitch) = accelerationFallback(prevDiff, diff, onCrosshair, distance)

        // --- Blend spring vs acceleration by weight ---
        val blendedYawStep = springStepYaw * (1f - aggressiveWeight) + accYaw * aggressiveWeight
        val blendedPitchStep = springStepPitch * (1f - aggressiveWeight) + accPitch * aggressiveWeight

        // Apply sigmoid deceleration when close to target
        val decelFactor = if (sigmoid.enabled) computeSigmoidFactor(diff.length(), sigmoid.steepness, sigmoid.midpoint) else 1f

        var finalYaw = currentRotation.yaw + blendedYawStep * decelFactor
        var finalPitch = currentRotation.pitch + blendedPitchStep * decelFactor

        // micro adjustments (tremor / tiny corrections)
        if (micro.enabled) {
            finalYaw += microTremor(micro.yawAmp, micro.freq)
            finalPitch += microTremor(micro.pitchAmp, micro.freq)
        }

        // clamp & wrap angles
        finalYaw = MathHelper.wrapAngleTo180_float(finalYaw)
        finalPitch = finalPitch.coerceIn(-90f, 90f)

        return Rotation(finalYaw, finalPitch)
    }

    override fun calculateTicks(currentRotation: Rotation, targetRotation: Rotation): Int {
        val prevRotation = RotationManager.previousRotation ?: player.lastRotation
        val prevDiff = prevRotation.rotationDeltaTo(currentRotation)
        val diff = currentRotation.rotationDeltaTo(targetRotation)

        if (MathHelper.approximatelyEquals(diff.deltaYaw, 0f) &&
            MathHelper.approximatelyEquals(diff.deltaPitch, 0f)) {
            return 0
        }

        // representative step uses blended approach (spring disabled -> fallback)
        val (yawStep, pitchStep) = computeRepresentativeStep(prevDiff, diff)
        if (yawStep == 0f && pitchStep == 0f) return 0

        val ticksH = floor(abs(diff.deltaYaw) / abs(yawStep)).takeIf { !it.isNaN() } ?: 0.0
        val ticksV = floor(abs(diff.deltaPitch) / abs(pitchStep)).takeIf { !it.isNaN() } ?: 0.0

        return max(ticksH, ticksV).toInt()
    }

    // === Helpers ===

    private fun applyEma(input: Rotation): Rotation {
        if (!ema.enabled) return input
        val a = ema.alpha
        if (emaYaw == null || emaPitch == null || emaCount < ema.initWindow) {
            emaYaw = (emaYaw ?: input.yaw)
            emaPitch = (emaPitch ?: input.pitch)
            emaCount++
        }
        emaYaw = a * input.yaw + (1f - a) * (emaYaw ?: input.yaw)
        emaPitch = a * input.pitch + (1f - a) * (emaPitch ?: input.pitch)
        return Rotation(emaYaw ?: input.yaw, emaPitch ?: input.pitch)
    }

    private fun microOnly(current: Rotation): Rotation {
        // only tiny tremor while waiting for reaction
        val y = current.yaw + microTremor(micro.yawAmp, micro.freq) * 0.4f
        val p = (current.pitch + microTremor(micro.pitchAmp, micro.freq) * 0.4f).coerceIn(-90f, 90f)
        return Rotation(y, p)
    }

    private fun microTremor(amp: Float, freq: Float): Float {
        // small combined sinus + random noise
        val t = System.currentTimeMillis() / 1000.0
        val s = (sin(t * freq) * 0.5 + (Math.random() - 0.5) * 0.6) * amp
        return s.toFloat()
    }

    private fun computeSigmoidFactor(diffLen: Float, steep: Float, midpoint: Float): Float {
        // map diffLen to 0..1 (approx using 120 deg full scale like Acceleration)
        val scaled = (diffLen / 120f).coerceIn(0f, 1f)
        val sig = (1.0 / (1.0 + exp((-steep * (scaled - midpoint)).toDouble()))).toFloat()
        return sig.coerceIn(0f, 1f)
    }

    private fun lerpAngle(a: Float, b: Float, w: Float): Float {
        val diff = RotationUtil.angleDifference(b, a)
        return a + diff * w
    }

    private fun accelerationFallback(prevDiff: RotationDelta, diff: RotationDelta, crosshair: Boolean, distance: Double): FloatFloatPair {
        val aYaw = baseYawAccel
        val aPitch = basePitchAccel

        val yawRange = -aYaw.random()..aYaw.random()
        val pitchRange = -aPitch.random()..aPitch.random()

        val yawAccel = RotationUtil.angleDifference(diff.deltaYaw, prevDiff.deltaYaw).coerceIn(yawRange)
        val pitchAccel = RotationUtil.angleDifference(diff.deltaPitch, prevDiff.deltaPitch).coerceIn(pitchRange)

        return FloatFloatPair.of(prevDiff.deltaYaw + yawAccel, prevDiff.deltaPitch + pitchAccel)
    }

    private fun computeRepresentativeStep(prevDiff: RotationDelta, diff: RotationDelta): FloatFloatPair {
        // approximate step similar to process blending: use spring estimate if enabled
        return if (spring.enabled) {
            val errYaw = RotationUtil.angleDifference(diff.deltaYaw, 0f)
            val errPitch = RotationUtil.angleDifference(diff.deltaPitch, 0f)
            val approxVelYaw = (spring.stiffness * errYaw) / (spring.damping + 1f)
            val approxVelPitch = (spring.stiffness * errPitch) / (spring.damping + 1f)
            FloatFloatPair.of(approxVelYaw.coerceIn(-spring.maxStep..spring.maxStep), approxVelPitch)
        } else {
            accelerationFallback(prevDiff, diff, false, 0.0)
        }
    }
}
