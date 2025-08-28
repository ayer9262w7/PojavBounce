package net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl

import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.AngleSmoothProcessor
import net.ccbluex.liquidbounce.utils.aiming.Rotation
import net.ccbluex.liquidbounce.utils.math.MathUtils
import net.ccbluex.liquidbounce.utils.timing.MSTimer
import net.minecraft.entity.Entity
import net.minecraft.util.MathHelper
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class UltimateAimProcessor(
    private val trackingStrength: Float = 95f,
    private val evasionLevel: Float = 85f,
    private val predictionPower: Float = 90f
) : AngleSmoothProcessor {

    private val timer = MSTimer()
    private var lastRotation: Rotation? = null
    private var movementPattern = 0
    private var patternChangeTimer = MSTimer()
    private val neuralPatterns = mutableListOf<FloatArray>()
    private var currentPattern = 0

    init {
        // Khởi tạo neural patterns
        repeat(5) {
            neuralPatterns.add(FloatArray(10) { Random.nextFloat() * 2 - 1 })
        }
    }

    override fun smoothAngle(
        currentRotation: Rotation,
        targetRotation: Rotation,
        targetEntity: Entity?
    ): Rotation {
        if (lastRotation == null) {
            lastRotation = currentRotation
            timer.reset()
            patternChangeTimer.reset()
            return currentRotation
        }

        // Thay đổi pattern định kỳ
        if (patternChangeTimer.hasTimePassed(Random.nextLong(2000, 5000))) {
            movementPattern = Random.nextInt(0, 5)
            currentPattern = Random.nextInt(0, neuralPatterns.size)
            patternChangeTimer.reset()
        }

        // Prediction movement
        val predictedRotation = if (targetEntity != null && predictionPower > 0) {
            predictTargetMovement(currentRotation, targetRotation, targetEntity)
        } else {
            targetRotation
        }

        // Adaptive smoothing
        val adaptiveSmoothness = calculateAdaptiveSmoothness(targetEntity)

        // Neural influence
        val neuralInfluence = neuralPatterns[currentPattern][timer.time.toInt() % 10] * 0.5f

        // Calculate smooth angles
        var yaw = calculateSmoothYaw(
            lastRotation!!.yaw,
            predictedRotation.yaw,
            adaptiveSmoothness,
            neuralInfluence
        )
        
        var pitch = calculateSmoothPitch(
            lastRotation!!.pitch,
            predictedRotation.pitch,
            adaptiveSmoothness,
            neuralInfluence
        )

        // Anti-detection noise
        if (evasionLevel > 70f) {
            val evasionNoise = generateEvasionNoise()
            yaw += evasionNoise.first
            pitch += evasionNoise.second
        }

        // Normalize angles
        yaw = MathHelper.wrapAngleTo180_float(yaw)
        pitch = pitch.coerceIn(-90f, 90f)

        lastRotation = Rotation(yaw, pitch)
        return lastRotation!!
    }

    private fun predictTargetMovement(
        current: Rotation,
        target: Rotation,
        entity: Entity
    ): Rotation {
        val velocityX = entity.motionX
        val velocityZ = entity.motionZ
        val speed = MathHelper.sqrt_double(velocityX * velocityX + velocityZ * velocityZ).toFloat()
        
        val predictionFactor = predictionPower / 100f * 0.3f
        val leadDistance = speed * predictionFactor * 20f

        val predictedYaw = target.yaw + atan2(velocityZ, velocityX).toFloat() * 180f / Math.PI.toFloat() * leadDistance
        val predictedPitch = target.pitch

        return Rotation(predictedYaw, predictedPitch)
    }

    private fun calculateAdaptiveSmoothness(target: Entity?): Float {
        if (target == null) return trackingStrength / 100f
        
        val speed = MathHelper.sqrt_double(
            target.motionX * target.motionX + 
            target.motionZ * target.motionZ
        ).toFloat()
        
        return (trackingStrength / 100f) * (1f - speed.coerceIn(0f, 0.5f) * 2f)
    }

    private fun calculateSmoothYaw(
        current: Float,
        target: Float,
        smoothness: Float,
        neuralInfluence: Float
    ): Float {
        val base = MathUtils.lerp(current, target, 1f - smoothness)
        return base + neuralInfluence * (1f - smoothness) * 5f
    }

    private fun calculateSmoothPitch(
        current: Float,
        target: Float,
        smoothness: Float,
        neuralInfluence: Float
    ): Float {
        val base = MathUtils.lerp(current, target, 1f - smoothness)
        return base + neuralInfluence * (1f - smoothness) * 3f
    }

    private fun generateEvasionNoise(): Pair<Float, Float> {
        val time = System.currentTimeMillis() / 1000.0
        val noiseYaw = (sin(time * 1.5) * 0.15 * (evasionLevel / 100f)).toFloat()
        val noisePitch = (cos(time * 1.2) * 0.1 * (evasionLevel / 100f)).toFloat()
        
        return Pair(noiseYaw, noisePitch)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UltimateAimProcessor) return false
        
        return trackingStrength == other.trackingStrength &&
               evasionLevel == other.evasionLevel &&
               predictionPower == other.predictionPower
    }

    override fun hashCode(): Int {
        var result = trackingStrength.hashCode()
        result = 31 * result + evasionLevel.hashCode()
        result = 31 * result + predictionPower.hashCode()
        return result
    }
}