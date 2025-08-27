package net.ccbluex.liquidbounce.features.module.modules.combat.killaura

import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import kotlin.math.*

/**
 * MovementAnalyzer - Module chuyên phân tích và dự đoán chuyển động với độ chính xác cao.
 * Phiên bản tương thích với Minecraft 1.21.4.
 */
object MovementAnalyzer {
    // --- Cấu hình ---
    private const val MOVEMENT_HISTORY_SIZE = 20
    private const val PREDICTION_TICKS = 3
    private const val MAX_REASONABLE_ACCELERATION = 5.0
    private const val ANOMALY_DETECTION_THRESHOLD = 3.0

    // --- Bộ nhớ chuyển động ---
    private val velocityHistory = mutableMapOf<Int, ArrayDeque<Vec3d>>()
    private val positionHistory = mutableMapOf<Int, ArrayDeque<Vec3d>>()
    private val entityUpdateTimes = mutableMapOf<Int, Long>()

    fun recordEntityState(entity: Entity) {
        val entityId = entity.id
        val currentTime = System.currentTimeMillis()

        val velHistory = velocityHistory.getOrPut(entityId) { ArrayDeque(MOVEMENT_HISTORY_SIZE) }
        val posHistory = positionHistory.getOrPut(entityId) { ArrayDeque(MOVEMENT_HISTORY_SIZE) }

        if (velHistory.size >= MOVEMENT_HISTORY_SIZE) velHistory.removeFirst()
        if (posHistory.size >= MOVEMENT_HISTORY_SIZE) posHistory.removeFirst()

        // NOTE: depending on Minecraft mapping/version these properties may be named differently.
        velHistory.addLast(entity.velocity)
        posHistory.addLast(entity.pos)
        entityUpdateTimes[entityId] = currentTime
    }

    fun cleanupEntityData(entityId: Int) {
        velocityHistory.remove(entityId)
        positionHistory.remove(entityId)
        entityUpdateTimes.remove(entityId)
    }

    fun getSpeedTowardsTarget(source: Entity, target: Entity): Double {
        val sourceVelocity = source.velocity
        val directionToTarget = target.pos.subtract(source.pos).normalize()
        val speedTowardsTargetPerTick = sourceVelocity.dotProduct(directionToTarget)
        return max(0.0, speedTowardsTargetPerTick * 20.0)
    }

    fun getMovementDirection(entity: Entity): Vec3d {
        return if (entity.velocity.lengthSquared() > 0.001) {
            entity.velocity.normalize()
        } else {
            Vec3d.ZERO
        }
    }

    fun getAngleToTarget(source: Entity, target: Entity): Double {
        val directionToTarget = target.pos.subtract(source.pos).normalize()
        val movementDirection = getMovementDirection(source)

        if (movementDirection == Vec3d.ZERO) return 180.0

        val dotProduct = movementDirection.dotProduct(directionToTarget)
        val clampedDot = dotProduct.coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(clampedDot))
    }

    fun getCurrentAcceleration(entity: Entity): Vec3d {
        val history = velocityHistory[entity.id] ?: return Vec3d.ZERO
        if (history.size < 2) return Vec3d.ZERO

        val currentVelocity = history.last()
        val previousVelocity = history[history.size - 2]
        return currentVelocity.subtract(previousVelocity).multiply(20.0)
    }

    fun predictFuturePosition(entity: Entity, ticksAhead: Int = PREDICTION_TICKS): Vec3d {
        val entityId = entity.id
        val history = positionHistory[entityId] ?: return entity.pos
        if (history.size < 2) return entity.pos

        val n = history.size
        val points = history.toList()

        var sumT = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        var sumT2 = 0.0
        var sumTX = 0.0
        var sumTY = 0.0
        var sumTZ = 0.0

        for (i in 0 until n) {
            val t = (i - n + 1).toDouble()
            val pos = points[i]
            sumT += t
            sumX += pos.x
            sumY += pos.y
            sumZ += pos.z
            sumT2 += t * t
            sumTX += t * pos.x
            sumTY += t * pos.y
            sumTZ += t * pos.z
        }

        val denominator = n * sumT2 - sumT * sumT
        if (abs(denominator) < 0.001) return entity.pos

        val slopeX = (n * sumTX - sumT * sumX) / denominator
        val slopeY = (n * sumTY - sumT * sumY) / denominator
        val slopeZ = (n * sumTZ - sumT * sumZ) / denominator

        val futureT = (n + ticksAhead - 1).toDouble()
        val predictedX = (sumX + slopeX * (futureT - sumT / n)) / n
        val predictedY = (sumY + slopeY * (futureT - sumT / n)) / n
        val predictedZ = (sumZ + slopeZ * (futureT - sumT / n)) / n

        return Vec3d(predictedX, predictedY, predictedZ)
    }

    fun predictFutureVelocity(entity: Entity): Vec3d {
        val history = velocityHistory[entity.id] ?: return entity.velocity
        if (history.size < 2) return entity.velocity

        val currentVel = history.last()
        val previousVel = history[history.size - 2]
        val acceleration = currentVel.subtract(previousVel)

        return currentVel.add(acceleration)
    }

    fun getPredictedMovementInPingTime(source: Entity, target: Entity, pingMs: Long): Double {
        val pingSeconds = pingMs / 1000.0
        val currentSpeed = getSpeedTowardsTarget(source, target)

        val predictedVelocity = predictFutureVelocity(source)
        val predictedSpeedTowardsTarget = predictedVelocity.dotProduct(
            target.pos.subtract(source.pos).normalize()
        ) * 20.0

        val blendFactor = 0.7
        val blendedSpeed = (currentSpeed * blendFactor) + (predictedSpeedTowardsTarget * (1 - blendFactor))

        return blendedSpeed * pingSeconds
    }

    fun getAimOffsetForPing(source: Entity, target: Entity, pingMs: Long): Vec3d {
        val pingTicks = (pingMs / 50.0).roundToInt()
        val futureTargetPos = predictFuturePosition(target, pingTicks)
        val futureSourcePos = predictFuturePosition(source, pingTicks)

        return futureTargetPos.subtract(futureSourcePos).normalize()
    }

    fun isMovementAnomalous(entity: Entity): Boolean {
        val history = velocityHistory[entity.id] ?: return false
        if (history.size < 3) return false

        val accelerations = mutableListOf<Double>()
        for (i in 1 until history.size) {
            val acceleration = history[i].subtract(history[i - 1]).length() * 20.0
            accelerations.add(acceleration)
        }

        if (accelerations.any { it > MAX_REASONABLE_ACCELERATION }) {
            return true
        }

        val mean = accelerations.average()
        val stdDev = sqrt(accelerations.map { (it - mean) * (it - mean) }.average())

        return accelerations.any { abs(it - mean) > ANOMALY_DETECTION_THRESHOLD * stdDev }
    }

    fun getPredictionConfidence(entity: Entity): Double {
        val entityId = entity.id
        val velHistory = velocityHistory[entityId] ?: return 0.5
        val posHistory = positionHistory[entityId] ?: return 0.5

        if (velHistory.size < 3 || posHistory.size < 3) return 0.5

        val velocities = velHistory.toList()
        val meanVel = velocities.map { it.length() }.average()
        val velocityVariance = velocities.map { (it.length() - meanVel) * (it.length() - meanVel) }.average()

        val directions = velocities.map { if (it.lengthSquared() > 0.001) it.normalize() else Vec3d.ZERO }
        val directionChanges = mutableListOf<Double>()
        for (i in 1 until directions.size) {
            if (directions[i] != Vec3d.ZERO && directions[i - 1] != Vec3d.ZERO) {
                val dot = directions[i].dotProduct(directions[i - 1])
                directionChanges.add(acos(dot.coerceIn(-1.0, 1.0)))
            }
        }

        val avgDirectionChange = if (directionChanges.isNotEmpty()) directionChanges.average() else 0.0

        val velocityStability = kotlin.math.exp(-velocityVariance / 5.0)
        val directionStability = kotlin.math.exp(-avgDirectionChange / 0.5)
        val dataAmount = min(1.0, velHistory.size.toDouble() / MOVEMENT_HISTORY_SIZE)

        return (velocityStability * 0.4 + directionStability * 0.4 + dataAmount * 0.2).coerceIn(0.0, 1.0)
    }

    fun estimateTimeToReachTarget(source: Entity, target: Entity): Double {
        val distance = source.pos.distanceTo(target.pos)
        val speedTowardsTarget = getSpeedTowardsTarget(source, target)

        if (speedTowardsTarget <= 0.1) return Double.MAX_VALUE

        return distance / speedTowardsTarget
    }

    fun isMovingTowardsTarget(source: Entity, target: Entity): Boolean {
        val angle = getAngleToTarget(source, target)
        return angle < 90.0
    }
}