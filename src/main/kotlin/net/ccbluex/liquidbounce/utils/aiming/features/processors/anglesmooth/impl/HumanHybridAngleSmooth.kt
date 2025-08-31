package net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl

import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.AngleSmoothProcessor
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.rotation.Rotation
import net.ccbluex.liquidbounce.utils.aiming.rotation.RotationDelta
import net.ccbluex.liquidbounce.utils.aiming.rotation.RotationManager
import kotlin.math.*

/**
 * HumanHybridAngleSmooth (patched)
 * - Fix spin 360°/flip 180° issues by adding:
 *   - Momentum smoothing of prevDiff
 *   - Clamp prediction & jitter offsets
 *   - Reset when target changes
 *   - Anti-oscillation & anti-flip guards
 */
class HumanHybridAngleSmooth : AngleSmoothProcessor("HumanHybrid") {

    private var lastTargetId: Int? = null
    private var lastPrevYaw: Float = 0f

    // Configurable constants (simplified for Pojav integration)
    private val momentum = 0.85f
    private val predictionClamp = 0.45f
    private val oscillationDamp = 0.5f
    private val maxStepDefault = 14f
    private val jitterAmp = 1.0f

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        prevDiff: RotationDelta
    ): RotationDelta {
        val target = rotationTarget.rotation ?: return prevDiff

        // Reset if new target entity
        val entityId = rotationTarget.entity?.entityId
        if (entityId != null && entityId != lastTargetId) {
            lastPrevYaw = 0f
            lastTargetId = entityId
        }

        // Smooth previous diff using momentum
        val smoothedPrevYaw = lastPrevYaw * (1 - momentum) + prevDiff.deltaYaw * momentum
        val smoothedPrev = RotationDelta(smoothedPrevYaw, prevDiff.deltaPitch)

        // Compute delta
        val deltaYaw = wrapDegrees(target.yaw - currentRotation.yaw)
        val deltaPitch = wrapDegrees(target.pitch - currentRotation.pitch)

        // Prediction offset (clamped)
        val humanYawOffset = (smoothedPrev.deltaYaw).coerceIn(-abs(deltaYaw) * predictionClamp, abs(deltaYaw) * predictionClamp)
        val humanPitchOffset = (smoothedPrev.deltaPitch).coerceIn(-abs(deltaPitch) * predictionClamp, abs(deltaPitch) * predictionClamp)

        var yawStep = deltaYaw * 0.15f + humanYawOffset
        var pitchStep = deltaPitch * 0.15f + humanPitchOffset

        // Add jitter (small sinusoidal)
        val t = (System.nanoTime() / 1.0E7).toFloat()
        yawStep += (sin(t) * jitterAmp * 0.2f)
        pitchStep += (cos(t) * jitterAmp * 0.2f)

        // Anti-oscillation: damp if flipping sign strongly
        if (sign(yawStep) != sign(smoothedPrev.deltaYaw) && abs(yawStep) > 2f * abs(smoothedPrev.deltaYaw)) {
            yawStep *= (1 - oscillationDamp).toFloat()
        }

        // Anti-flip: don't overshoot beyond target delta
        if (abs(yawStep) > abs(deltaYaw) * 1.2f) {
            yawStep = deltaYaw * 0.9f
        }

        // Clamp max step
        yawStep = yawStep.coerceIn(-maxStepDefault, maxStepDefault)
        pitchStep = pitchStep.coerceIn(-maxStepDefault, maxStepDefault)

        // Update lastPrevYaw for next tick
        lastPrevYaw = smoothedPrevYaw

        return RotationDelta(yawStep, pitchStep)
    }

    private fun wrapDegrees(value: Float): Float {
        var v = value
        while (v <= -180.0f) v += 360.0f
        while (v > 180.0f) v -= 360.0f
        return v
    }
}
