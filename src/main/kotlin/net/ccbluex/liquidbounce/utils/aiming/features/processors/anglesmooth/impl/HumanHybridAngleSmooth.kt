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
import net.minecraft.util.math.MathHelper
import kotlin.math.*
import kotlin.random.Random

// Đặt tên mới để thể hiện sự nâng cấp, nhưng tên trong game vẫn là "HumanHybridV2" cho quen thuộc
class AdvancedHybridAngleSmooth(parent: ChoiceConfigurable<*>) : AngleSmooth("HumanHybridV2", parent) {

    // --- CÁC THÀNH PHẦN CỐT LÕI ---
    
    // 1. Lấy từ HumanHybrid: Gia tốc cơ bản và ảnh hưởng khoảng cách
    private inner class BaseAcceleration : ToggleableConfigurable(this, "BaseAcceleration", true) {
        val baseYawAccel by floatRange("BaseYawAccel", 14f..22f, 1f..180f)
        val basePitchAccel by floatRange("BasePitchAccel", 12f..20f, 1f..180f)
        val distanceCoef by float("DistanceCoef", -1.0f, -5f..5f)
        val crosshairBoost by floatRange("CrosshairBoost", 16f..22f, 1f..180f)
    }

    // 2. Lấy từ Acceleration: Cơ chế giảm tốc khi gần mục tiêu -> Tăng tracking
    private inner class SigmoidDeceleration : ToggleableConfigurable(this, "SigmoidDeceleration", true) {
        val steepness by float("Steepness", 10f, 0.0f..20f)
        val midpoint by float("Midpoint", 0.3f, 0.0f..1.0f)

        fun computeFactor(rotationDifference: Float): Float {
            if (!enabled) return 1.0f
            val scaledDifference = rotationDifference / 180f // Chuẩn hóa góc lệch
            val sigmoid = 1 / (1 + exp((-steepness * (scaledDifference - midpoint)).toDouble()))
            return sigmoid.toFloat().coerceIn(0.1f, 1.0f) // Đảm bảo không bao giờ dừng hẳn
        }
    }

    // 3. Nâng cấp: Kết hợp Prediction và Jitter từ HumanHybrid vào một module riêng -> Tăng humanization
    private inner class Humanization : ToggleableConfigurable(this, "Humanization", true) {
        val predictionStrength by float("PredictionStrength", 0.25f, 0f..2f)
        val humanJitter by float("HumanJitter", 0.6f, 0f..5f)

        fun getOffsets(diff: RotationDelta): FloatFloatPair {
            if (!enabled) return FloatFloatPair.of(0f, 0f)
            
            // Lấy logic Prediction từ HumanHybrid
            val predYawOffset = diff.deltaYaw * predictionStrength
            val predPitchOffset = diff.deltaPitch * predictionStrength

            // Lấy logic Jitter (run lắc) từ HumanHybrid
            val jitterYaw = (sin(System.nanoTime() * 1e-9 * 3.0) * 0.5 * humanJitter).toFloat()
            val jitterPitch = (cos(System.nanoTime() * 1e-9 * 2.7) * 0.4 * humanJitter).toFloat()

            return FloatFloatPair.of(predYawOffset + jitterYaw, predPitchOffset + jitterPitch)
        }
    }

    private val baseAcceleration = tree(BaseAcceleration())
    private val sigmoidDeceleration = tree(SigmoidDeceleration())
    private val humanization = tree(Humanization())
    
    private val maxStepDefault by float("MaxStepPerTick", 15f, 1f..180f)

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

        val (yawStep, pitchStep) = computeTurnSpeed(prevDiff, diff, crosshair, distance)

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

        val (yawStep, pitchStep) = computeTurnSpeed(prevDiff, diff, false, 0.0)
        if (abs(yawStep) < 1e-6f && abs(pitchStep) < 1e-6f) return 0

        val ticksH = floor(abs(diff.deltaYaw) / abs(yawStep)).let { if (it.isNaN()) 0.0 else it }
        val ticksV = floor(abs(diff.deltaPitch) / abs(pitchStep)).let { if (it.isNaN()) 0.0 else it }

        return max(ticksH, ticksV).toInt().coerceAtLeast(1)
    }

    @Suppress("LongParameterList")
    private fun computeTurnSpeed(
        prevDiff: RotationDelta,
        diff: RotationDelta,
        crosshair: Boolean,
        distance: Double
    ): FloatFloatPair {
        // --- BƯỚC 1: TÍNH TOÁN GIA TỐC CƠ BẢN (Lấy ý tưởng từ HumanHybrid) ---
        val distCoef = if (distance.isFinite()) {
            (baseAcceleration.distanceCoef * distance).toFloat()
        } else {
            0f
        }

        val useCrosshairBoost = baseAcceleration.enabled && crosshair
        val yawInterval = if (useCrosshairBoost) baseAcceleration.crosshairBoost else baseAcceleration.baseYawAccel
        val pitchInterval = baseAcceleration.basePitchAccel

        fun sample(range: ClosedFloatingPointRange<Float>): Float = Random.nextFloat() * (range.endInclusive - range.start) + range.start
        
        // Tạo gia tốc bất đối xứng (đặc trưng của HumanHybrid)
        val yawRandA = sample(yawInterval)
        val yawRandB = sample(yawInterval)
        val pitchRandA = sample(pitchInterval)
        val pitchRandB = sample(pitchInterval)

        val yawAccelRange = min(-yawRandA, yawRandB) + distCoef .. max(-yawRandA, yawRandB) + distCoef
        val pitchAccelRange = min(-pitchRandA, pitchRandB) + distCoef .. max(-pitchRandA, pitchRandB) + distCoef
        
        val neededYawAccel = RotationUtil.angleDifference(diff.deltaYaw, prevDiff.deltaYaw)
        val neededPitchAccel = RotationUtil.angleDifference(diff.deltaPitch, prevDiff.deltaPitch)

        var finalYawAccel = neededYawAccel.coerceIn(yawAccelRange)
        var finalPitchAccel = neededPitchAccel.coerceIn(pitchAccelRange)

        // --- BƯỚC 2: ÁP DỤNG GIẢM TỐC (Lấy từ Acceleration để tăng tracking) ---
        val decelerationFactor = sigmoidDeceleration.computeFactor(diff.length())
        finalYawAccel *= decelerationFactor
        finalPitchAccel *= decelerationFactor

        // --- BƯỚC 3: THÊM CÁC YẾU TỐ "CON NGƯỜI" (Nâng cấp từ HumanHybrid) ---
        val (humanYawOffset, humanPitchOffset) = humanization.getOffsets(diff)

        // --- BƯỚC 4: TỔNG HỢP KẾT QUẢ ---
        var yawStep = prevDiff.deltaYaw + finalYawAccel + humanYawOffset
        var pitchStep = prevDiff.deltaPitch + finalPitchAccel + humanPitchOffset
        
        // Giới hạn tốc độ quay tối đa mỗi tick
        yawStep = MathHelper.wrapDegrees(yawStep).coerceIn(-maxStepDefault, maxStepDefault)
        pitchStep = pitchStep.coerceIn(-maxStepDefault, maxStepDefault)
        
        return FloatFloatPair.of(yawStep, pitchStep)
    }
}