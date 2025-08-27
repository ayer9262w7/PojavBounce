package net.ccbluex.liquidbounce.features.module.modules.combat.killaura

import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import kotlin.math.*

/**
 * MovementAnalyzer - Module chuyên phân tích và dự đoán chuyển động với độ chính xác cao.
 * Phiên bản nâng cao với khả năng dự báo chuyển động, phát hiện bất thường và tích hợp mạng.
 * Phiên bản tương thích với Minecraft 1.21.4.
 * 
 * @author Gemini & The Tester
 * @version 2.0 (August 2025)
 */
object MovementAnalyzer {
    // --- Cấu hình ---
    private const val MOVEMENT_HISTORY_SIZE = 20 // Số lượng frame chuyển động để lưu lại
    private const val PREDICTION_TICKS = 3 // Số tick để dự đoán trước
    private const val MAX_REASONABLE_ACCELERATION = 5.0 // Ngưỡng gia tốc hợp lý (blocks/s²)
    private const val ANOMALY_DETECTION_THRESHOLD = 3.0 // Ngưỡng phát hiện bất thường (độ lệch chuẩn)

    // --- Bộ nhớ chuyển động ---
    private val velocityHistory = mutableMapOf<Int, ArrayDeque<Vec3d>>()
    private val positionHistory = mutableMapOf<Int, ArrayDeque<Vec3d>>()
    private val entityUpdateTimes = mutableMapOf<Int, Long>()

    // --- Các hàm thu thập dữ liệu cơ bản ---

    /**
     * Ghi lại trạng thái hiện tại của entity để phân tích xu hướng.
     */
    fun recordEntityState(entity: Entity) {
        val entityId = entity.id
        val currentTime = System.currentTimeMillis()
        
        // Khởi tạo lịch sử nếu chưa tồn tại
        val velHistory = velocityHistory.getOrPut(entityId) { ArrayDeque(MOVEMENT_HISTORY_SIZE) }
        val posHistory = positionHistory.getOrPut(entityId) { ArrayDeque(MOVEMENT_HISTORY_SIZE) }
        
        // Loại bỏ dữ liệu cũ nếu đạt kích thước tối đa
        if (velHistory.size >= MOVEMENT_HISTORY_SIZE) velHistory.removeFirst()
        if (posHistory.size >= MOVEMENT_HISTORY_SIZE) posHistory.removeFirst()
        
        // Thêm dữ liệu mới
        // NOTE: sử dụng entity.velocity và entity.pos như trong code gốc của bạn; nếu API khác, đổi tương ứng.
        velHistory.addLast(entity.velocity)
        posHistory.addLast(entity.pos)
        entityUpdateTimes[entityId] = currentTime
    }

    /**
     * Xóa dữ liệu lịch sử của entity khi không cần thiết.
     */
    fun cleanupEntityData(entityId: Int) {
        velocityHistory.remove(entityId)
        positionHistory.remove(entityId)
        entityUpdateTimes.remove(entityId)
    }

    // --- Phân tích chuyển động cơ bản ---

    /**
     * Tính toán tốc độ của 'source' đang hướng về phía 'target'.
     */
    fun getSpeedTowardsTarget(source: Entity, target: Entity): Double {
        val sourceVelocity = source.velocity
        val directionToTarget = target.pos.subtract(source.pos).normalize()
        val speedTowardsTargetPerTick = sourceVelocity.dotProduct(directionToTarget)
        return max(0.0, speedTowardsTargetPerTick * 20.0) // Chuyển đổi sang blocks/second
    }

    /**
     * Tính toán vector hướng di chuyển thực tế của entity (đã chuẩn hóa).
     */
    fun getMovementDirection(entity: Entity): Vec3d {
        return if (entity.velocity.lengthSquared() > 0.001) {
            entity.velocity.normalize()
        } else {
            Vec3d.ZERO
        }
    }

    /**
     * Tính toán góc lệch giữa hướng di chuyển của entity và hướng tới mục tiêu.
     * @return Góc lệch tính bằng độ (0-180)
     */
    fun getAngleToTarget(source: Entity, target: Entity): Double {
        val directionToTarget = target.pos.subtract(source.pos).normalize()
        val movementDirection = getMovementDirection(source)
        
        if (movementDirection == Vec3d.ZERO) return 180.0
        
        val dotProduct = movementDirection.dotProduct(directionToTarget)
        val clampedDot = dotProduct.coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(clampedDot))
    }

    /**
     * Tính toán gia tốc hiện tại của entity (thay đổi vận tốc).
     */
    fun getCurrentAcceleration(entity: Entity): Vec3d {
        val history = velocityHistory[entity.id] ?: return Vec3d.ZERO
        if (history.size < 2) return Vec3d.ZERO
        
        val currentVelocity = history.last()
        val previousVelocity = history[history.size - 2]
        return currentVelocity.subtract(previousVelocity).multiply(20.0) // Chuyển sang blocks/s²
    }

    // --- Dự đoán chuyển động nâng cao ---

    /**
     * Dự đoán vị trí tiếp theo của entity dựa trên lịch sử chuyển động.
     * @param ticksAhead Số tick muốn dự đoán trước
     * @return Vị trí dự đoán
     */
    fun predictFuturePosition(entity: Entity, ticksAhead: Int = PREDICTION_TICKS): Vec3d {
        val entityId = entity.id
        val history = positionHistory[entityId] ?: return entity.pos
        if (history.size < 2) return entity.pos
        
        // Phân tích xu hướng chuyển động sử dụng hồi quy tuyến tính đơn giản
        val n = history.size
        val points = history.toList()
        
        // Chuẩn bị dữ liệu cho hồi quy
        var sumT = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        var sumT2 = 0.0
        var sumTX = 0.0
        var sumTY = 0.0
        var sumTZ = 0.0
        
        for (i in 0 until n) {
            val t = (i - n + 1).toDouble() // Thời gian tương đối
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
        
        // Tính hệ số hồi quy cho từng trục
        val denominator = n * sumT2 - sumT * sumT
        if (abs(denominator) < 0.001) return entity.pos // Tránh chia cho 0
        
        val slopeX = (n * sumTX - sumT * sumX) / denominator
        val slopeY = (n * sumTY - sumT * sumY) / denominator
        val slopeZ = (n * sumTZ - sumT * sumZ) / denominator
        
        // Dự đoán vị trí tương lai
        val futureT = (n + ticksAhead - 1).toDouble()
        val predictedX = (sumX + slopeX * (futureT - sumT / n)) / n
        val predictedY = (sumY + slopeY * (futureT - sumT / n)) / n
        val predictedZ = (sumZ + slopeZ * (futureT - sumT / n)) / n
        
        return Vec3d(predictedX, predictedY, predictedZ)
    }

    /**
     * Dự đoán vận tốc tiếp theo của entity.
     */
    fun predictFutureVelocity(entity: Entity): Vec3d {
        val history = velocityHistory[entity.id] ?: return entity.velocity
        if (history.size < 2) return entity.velocity
        
        // Tính toán xu hướng vận tốc đơn giản
        val currentVel = history.last()
        val previousVel = history[history.size - 2]
        val acceleration = currentVel.subtract(previousVel)
        
        // Dự đoán vận tốc tiếp theo (giả định gia tốc không đổi)
        return currentVel.add(acceleration)
    }

    // --- Tích hợp với hệ thống mạng ---

    /**
     * Tính toán quãng đường dự đoán entity sẽ di chuyển trong thời gian ping.
     * @param pingMs Thời gian ping tính bằng mili giây
     * @return Quãng đường dự đoán (tính bằng blocks)
     */
    fun getPredictedMovementInPingTime(source: Entity, target: Entity, pingMs: Long): Double {
        val pingSeconds = pingMs / 1000.0
        val currentSpeed = getSpeedTowardsTarget(source, target)
        
        // Dự đoán vận tốc tại thời điểm tiếp theo
        val predictedVelocity = predictFutureVelocity(source)
        val predictedSpeedTowardsTarget = predictedVelocity.dotProduct(
            target.pos.subtract(source.pos).normalize()
        ) * 20.0 // Chuyển sang blocks/second
        
        // Kết hợp vận tốc hiện tại và dự đoán theo trọng số
        val blendFactor = 0.7 // Ưu tiên vận tốc hiện tại
        val blendedSpeed = (currentSpeed * blendFactor) + (predictedSpeedTowardsTarget * (1 - blendFactor))
        
        return blendedSpeed * pingSeconds
    }

    /**
     * Tính toán điểm ngắm bù trừ cho ping dựa trên chuyển động dự đoán.
     * @return Vector bù trừ cho điểm ngắm
     */
    fun getAimOffsetForPing(source: Entity, target: Entity, pingMs: Long): Vec3d {
        val pingTicks = (pingMs / 50.0).roundToInt() // Giả định 20 ticks/second
        val futureTargetPos = predictFuturePosition(target, pingTicks)
        val futureSourcePos = predictFuturePosition(source, pingTicks)
        
        return futureTargetPos.subtract(futureSourcePos).normalize()
    }

    // --- Phát hiện bất thường và an toàn ---

    /**
     * Kiểm tra xem chuyển động của entity có bất thường (có thể do cheat) hay không.
     */
    fun isMovementAnomalous(entity: Entity): Boolean {
        val history = velocityHistory[entity.id] ?: return false
        if (history.size < 3) return false
        
        // Tính toán gia tốc trong lịch sử
        val accelerations = mutableListOf<Double>()
        for (i in 1 until history.size) {
            val acceleration = history[i].subtract(history[i - 1]).length() * 20.0 // Chuyển sang blocks/s²
            accelerations.add(acceleration)
        }
        
        // Kiểm tra nếu có gia tốc vượt quá ngưỡng hợp lý
        if (accelerations.any { it > MAX_REASONABLE_ACCELERATION }) {
            return true
        }
        
        // Phân tích thống kê để phát hiện bất thường
        val mean = accelerations.average()
        val stdDev = sqrt(accelerations.map { (it - mean) * (it - mean) }.average())
        
        // Kiểm tra nếu có điểm dữ liệu nào vượt quá ngưỡng độ lệch chuẩn
        return accelerations.any { abs(it - mean) > ANOMALY_DETECTION_THRESHOLD * stdDev }
    }

    /**
     * Tính điểm tin cậy của dự đoán chuyển động (0.0 - 1.0).
     */
    fun getPredictionConfidence(entity: Entity): Double {
        val entityId = entity.id
        val velHistory = velocityHistory[entityId] ?: return 0.5
        val posHistory = positionHistory[entityId] ?: return 0.5
        
        if (velHistory.size < 3 || posHistory.size < 3) return 0.5
        
        // Tính độ ổn định của chuyển động dựa trên phương sai vận tốc
        val velocities = velHistory.toList()
        val meanVel = velocities.map { it.length() }.average()
        val velocityVariance = velocities.map { (it.length() - meanVel) * (it.length() - meanVel) }.average()
        
        // Tính độ ổn định của hướng di chuyển
        val directions = velocities.map { if (it.lengthSquared() > 0.001) it.normalize() else Vec3d.ZERO }
        val directionChanges = mutableListOf<Double>()
        for (i in 1 until directions.size) {
            if (directions[i] != Vec3d.ZERO && directions[i-1] != Vec3d.ZERO) {
                val dot = directions[i].dotProduct(directions[i-1])
                directionChanges.add(acos(dot.coerceIn(-1.0, 1.0)))
            }
        }
        
        val avgDirectionChange = if (directionChanges.isNotEmpty()) directionChanges.average() else 0.0
        
        // Kết hợp các yếu tố để tính điểm tin cậy
        val velocityStability = kotlin.math.exp(-velocityVariance / 5.0) // Ổn định vận tốc
        val directionStability = kotlin.math.exp(-avgDirectionChange / 0.5) // Ổn định hướng
        val dataAmount = min(1.0, velHistory.size.toDouble() / MOVEMENT_HISTORY_SIZE) // Độ đầy đủ dữ liệu
        
        return (velocityStability * 0.4 + directionStability * 0.4 + dataAmount * 0.2).coerceIn(0.0, 1.0)
    }

    // --- Tiện ích hỗ trợ ---

    /**
     * Ước tính thời gian để entity di chuyển đến vị trí mục tiêu.
     */
    fun estimateTimeToReachTarget(source: Entity, target: Entity): Double {
        val distance = source.pos.distanceTo(target.pos)
        val speedTowardsTarget = getSpeedTowardsTarget(source, target)
        
        if (speedTowardsTarget <= 0.1) return Double.MAX_VALUE // Không di chuyển về phía mục tiêu
        
        return distance / speedTowardsTarget
    }

    /**
     * Kiểm tra xem entity có đang di chuyển về phía mục tiêu hay không.
     */
    fun isMovingTowardsTarget(source: Entity, target: Entity): Boolean {
        val angle = getAngleToTarget(source, target)
        return angle < 90.0 // Di chuyển về phía trước mục tiêu
    }
}