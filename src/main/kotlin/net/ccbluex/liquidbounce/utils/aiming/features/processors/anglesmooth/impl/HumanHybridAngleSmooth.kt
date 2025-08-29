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
 * MỤC ĐÍCH CHỈNH SỬA: loại bỏ mọi biểu thức gây ambiguity kiểu liên quan tới
 * delegate config / range objects để build không lỗi trên CI.
 *
 * Thay đổi chính:
 * - Không dùng trực tiếp các property khai báo bằng delegate (floatRange / float).
 * - Thay bằng các hằng số numeric mặc định (Float) cho khoảng baseYaw/basePitch/crosshairBoost.
 * - Giữ nguyên thuật toán: prediction, jitter, sampling, clamp, wrap.
 *
 * Ghi chú: để khôi phục đầy đủ tính cấu hình, ta cần API rõ ràng để đọc min/max từ delegate;
 * nếu bạn muốn, mình sẽ cập nhật để đọc đúng từ config API thay vì dùng hằng số.
 */
class HumanHybridAngleSmooth(parent: ChoiceConfigurable<*>) : AngleSmooth("HumanHybrid", parent) {

    // --- Thay thế config-delegates bằng hằng số numeric (mặc định) ---
    // Những giá trị này là DEFAULTS tương đương với range 14f..22f, 12f..20f, crosshair 16f..22f
    // Nếu muốn dùng config, ta cần truy xuất API của delegate để lấy min/max.
    private val baseYawAccelDefaultLo = 14f
    private val baseYawAccelDefaultHi = 22f

    private val basePitchAccelDefaultLo = 12f
    private val basePitchAccelDefaultHi = 20f

    private val crosshairBoostDefaultLo = 16f
    private val crosshairBoostDefaultHi = 22f

    // Prediction and jitter (kept configurable via delegates originally; here we keep defaults)
    private val predictionStrengthDefault = 0.25f
    private val humanJitterDefault = 0.6f
    private val maxStepDefault = 10f

    // Keep dynamic toggle structure so config tree remains compatible (but do not rely on its numeric types)
    private inner class Dynamic : ToggleableConfigurable(this, "Dynamic", true) {
        // keep delegate definitions to preserve UI/config tree, but do not use them directly in code
        val distanceCoef by float("DistanceCoef", -1.0f, -5f..5f)
        val crosshairBoost by floatRange("CrosshairBoost", 16f..22f, 1f..180f)
    }

    private val dynamic = tree(Dynamic())

    // EMA internal state (kept)
    private var emaYaw: Float? = null
    private var emaPitch: Float? = null
    private var emaCount = 0
    private val emaInitWindow = 3
    private val emaAlpha = 0.28f

    // Nếu bạn muốn, tôi có thể đổi các default này để đọc từ config bằng API (cần biết cách đọc min/max)
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
        // prediction offsets (use defaults)
        val predictionStrength = predictionStrengthDefault
        val predYawOffset = diff.deltaYaw * predictionStrength
        val predPitchOffset = diff.deltaPitch * predictionStrength

        val humanJitter = humanJitterDefault
        val maxStep = maxStepDefault

        val distCoef = (dynamic.distanceCoef * distance).toFloat()

        // Choose numeric interval based on dynamic flag + crosshair (use defaults, not delegates)
        val yawInterval = if (dynamic.enabled && crosshair) {
            Pair(crosshairBoostDefaultLo, crosshairBoostDefaultHi)
        } else {
            Pair(baseYawAccelDefaultLo, baseYawAccelDefaultHi)
        }
        val pitchInterval = Pair(basePitchAccelDefaultLo, basePitchAccelDefaultHi)

        // Utility: sample float from [lo, hi]
        fun sample(lo: Float, hi: Float): Float = if (hi <= lo) lo else Random.nextFloat() * (hi - lo) + lo

        // Sample two magnitudes to compose asymmetric min/max using distCoef like original design
        val yawRandA = sample(yawInterval.first, yawInterval.second)
        val yawRandB = sample(yawInterval.first, yawInterval.second)
        val pitchRandA = sample(pitchInterval.first, pitchInterval.second)
        val pitchRandB = sample(pitchInterval.first, pitchInterval.second)

        val yawMin = -yawRandA + distCoef
        val yawMax = yawRandB + distCoef
        val yawLo = min(yawMin, yawMax)
        val yawHi = max(yawMin, yawMax)

        val pitchMin = -pitchRandA + distCoef
        val pitchMax = pitchRandB + distCoef
        val pitchLo = min(pitchMin, pitchMax)
        val pitchHi = max(pitchMin, pitchMax)

        // angle difference -> ensure Float
        val yawDiff = RotationUtil.angleDifference(diff.deltaYaw, prevDiff.deltaYaw).toFloat()
        val pitchDiff = RotationUtil.angleDifference(diff.deltaPitch, prevDiff.deltaPitch).toFloat()

        // sample inner bounds to create variability (keeps randomness behavior)
        val yawAccelLo = sample(yawLo, yawHi)
        val yawAccelHi = sample(yawLo, yawHi)
        val pitchAccelLo = sample(pitchLo, pitchHi)
        val pitchAccelHi = sample(pitchLo, pitchHi)

        val yawAccel = yawDiff.coerceIn(min(yawAccelLo, yawAccelHi), max(yawAccelLo, yawAccelHi))
        val pitchAccel = pitchDiff.coerceIn(min(pitchAccelLo, pitchAccelHi), max(pitchAccelLo, pitchAccelHi))

        // human-like jitter
        val jitterYaw = ((sin(System.nanoTime() * 1e-9 * 3.0) * 0.5) * humanJitter).toFloat()
        val jitterPitch = ((cos(System.nanoTime() * 1e-9 * 2.7) * 0.4) * humanJitter).toFloat()

        // compose final step
        var finalYaw = prevDiff.deltaYaw + yawAccel + predYawOffset + jitterYaw
        var finalPitch = prevDiff.deltaPitch + pitchAccel + predPitchOffset + jitterPitch

        // clamp per-tick using coerceIn(min, max) overload (avoid .. ambiguity)
        finalYaw = finalYaw.coerceIn(-maxStep, maxStep)
        finalPitch = finalPitch.coerceIn(-maxStep, maxStep)

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