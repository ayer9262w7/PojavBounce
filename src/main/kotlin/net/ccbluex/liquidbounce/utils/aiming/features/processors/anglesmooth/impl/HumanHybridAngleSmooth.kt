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
 * Sửa triệt để các điểm gây lỗi biên dịch liên quan đến ambiguity kiểu khi tạo range
 * và bảo đảm các toán tử coerceIn / coerce sử dụng kiểu Float nhất quán.
 *
 * Mục tiêu: build không lỗi và hành vi feature được giữ nguyên.
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

    // EMA internal state (keeps filtered predicted rotation) — chưa triển khai đầy đủ, giữ lại
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

        // đảm bảo dùng Double cho tính toán distance tương thích với facingEnemy
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
        // prediction offset from rotation delta (Float * Float => Float)
        val predYawOffset = diff.deltaYaw * predictionStrength
        val predPitchOffset = diff.deltaPitch * predictionStrength

        // dynamic/adaptive bases
        val aYawBase = if (dynamic.enabled && crosshair) dynamic.crosshairBoost else baseYawAccel
        val aPitchBase = basePitchAccel

        val distCoef = (dynamic.distanceCoef * distance).toFloat()

        // --- Robust random range handling (triệt để) ---
        // - Ép rõ ràng về Float (tránh Number&Comparable ambiguity)
        // - Đảm bảo min <= max bằng swap (min/max)
        // - Dùng ClosedFloatingPointRange<Float> rõ ràng
        val yawRandA = aYawBase.random().toFloat()
        val yawRandB = aYawBase.random().toFloat()
        val yawMin = (-yawRandA + distCoef)
        val yawMax = (yawRandB + distCoef)
        val yawLo = min(yawMin, yawMax)
        val yawHi = max(yawMin, yawMax)
        val yawRange: ClosedFloatingPointRange<Float> = yawLo..yawHi

        val pitchRandA = aPitchBase.random().toFloat()
        val pitchRandB = aPitchBase.random().toFloat()
        val pitchMin = (-pitchRandA + distCoef)
        val pitchMax = (pitchRandB + distCoef)
        val pitchLo = min(pitchMin, pitchMax)
        val pitchHi = max(pitchMin, pitchMax)
        val pitchRange: ClosedFloatingPointRange<Float> = pitchLo..pitchHi

        // angleDifference có thể trả Double ở một số impl -> ép về Float để coerceIn hoạt động
        val yawDiff = RotationUtil.angleDifference(diff.deltaYaw, prevDiff.deltaYaw).toFloat()
        val pitchDiff = RotationUtil.angleDifference(diff.deltaPitch, prevDiff.deltaPitch).toFloat()

        val yawAccel = yawDiff.coerceIn(yawRange)
        val pitchAccel = pitchDiff.coerceIn(pitchRange)

        // human-like jitter (Double -> Float)
        val jitterYaw = ((sin(System.nanoTime() * 1e-9 * 3.0) * 0.5) * humanJitter).toFloat()
        val jitterPitch = ((cos(System.nanoTime() * 1e-9 * 2.7) * 0.4) * humanJitter).toFloat()

        // final step composition (Float)
        var finalYaw = prevDiff.deltaYaw + yawAccel + predYawOffset + jitterYaw
        var finalPitch = prevDiff.deltaPitch + pitchAccel + predPitchOffset + jitterPitch

        // clamp per-tick: dùng coerceIn(min, max) overload để tránh ambiguity với range operator
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