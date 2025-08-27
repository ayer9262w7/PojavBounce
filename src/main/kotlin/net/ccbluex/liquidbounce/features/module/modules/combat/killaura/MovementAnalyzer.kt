package net.ccbluex.liquidbounce.features.module.modules.combat.killaura

// MovementAnalyzer.kt
// Singleton phân tích chuyển động: lưu lịch sử vị trí/ vận tốc, tính TPS, dự đoán vị trí tương lai (kinematic)

import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.max
import kotlin.math.sqrt

object MovementAnalyzer {
    // --- Config ---
    private const val MAX_HISTORY = 200 // lưu 200 tick (~10s)
    const val PREDICTION_TICKS = 2 // default ticks ahead (can override khi gọi)

    // --- Histories keyed by entity id ---
    private val positionHistory: MutableMap<Int, ConcurrentLinkedDeque<Vec3d>> = ConcurrentHashMap()
    private val velocityHistory: MutableMap<Int, ConcurrentLinkedDeque<Vec3d>> = ConcurrentHashMap()

    // --- TPS estimation using WorldTimeUpdate packets ---
    private var timeUpdatePackets = 0
    private var timeUpdateAccumulatedSeconds = 0.0
    private var lastTimeUpdate = -1L
    // serverTPS default 20.0
    @Volatile
    private var serverTPS: Double = 20.0

    // --- Public API ---

    /**
     * Ghi lại trạng thái entity (pos, vel) — gọi mỗi tick hoặc khi bạn có dữ liệu.
     * Bạn nên gọi function này từ chỗ bạn theo dõi entity (ví dụ mỗi tick).
     */
    fun recordEntityState(entity: Entity) {
        val id = entity.id
        val pos = getEntityPos(entity)
        val vel = getEntityVelocity(entity)

        // store positions
        val posQueue = positionHistory.computeIfAbsent(id) { ConcurrentLinkedDeque() }
        posQueue.addLast(pos)
        while (posQueue.size > MAX_HISTORY) posQueue.removeFirst()

        // store velocities
        val velQueue = velocityHistory.computeIfAbsent(id) { ConcurrentLinkedDeque() }
        velQueue.addLast(vel)
        while (velQueue.size > MAX_HISTORY) velQueue.removeFirst()
    }

    /**
     * Dự đoán vị trí tương lai bằng kinematic extrapolation:
     * lastPos, lastVel, acc (delta vel) -> integrate per-tick
     * ticksAhead: số tick để dự đoán (1 tick = 1/20s)
     */
    fun predictFuturePosition(entity: Entity, ticksAhead: Int = PREDICTION_TICKS): Vec3d {
        val id = entity.id
        val posHist = positionHistory[id]?.toList()
        val velHist = velocityHistory[id]?.toList()

        // nếu thiếu lịch sử, trả về vị trí hiện tại
        val lastPos = posHist?.lastOrNull() ?: getEntityPos(entity)
        var lastVel = velHist?.lastOrNull() ?: getEntityVelocity(entity)
        val prevVel = if (velHist != null && velHist.size >= 2) velHist[velHist.size - 2] else lastVel

        // acceleration (per-tick)
        val accPerTick = lastVel.subtract(prevVel)

        var predicted = lastPos
        for (i in 1..max(1, ticksAhead)) {
            // pos += vel + 0.5*acc
            predicted = predicted.add(lastVel.add(accPerTick.multiply(0.5)))
            // vel += acc
            lastVel = lastVel.add(accPerTick)
        }

        return predicted
    }

    /**
     * Trả về giá trị TPS gần đúng (đếm bằng WorldTimeUpdateS2CPacket).
     */
    fun getServerTPSValue(): Double {
        return serverTPS
    }

    /**
     * Lấy vị trí cuối cùng đã lưu (hoặc null nếu không có).
     */
    fun getLatestPosition(entityId: Int): Vec3d? {
        return positionHistory[entityId]?.lastOrNull()
    }

    // --- Simple anomaly detection: nếu thay đổi vận tốc đột ngột, coi là anomalous ---
    fun isMovementAnomalous(entity: Entity): Boolean {
        val id = entity.id
        val velHist = velocityHistory[id] ?: return false
        if (velHist.size < 2) return false
        val last = velHist.elementAt(velHist.size - 1)
        val prev = velHist.elementAt(velHist.size - 2)
        val lastMag = sqrt(last.x * last.x + last.y * last.y + last.z * last.z)
        val prevMag = sqrt(prev.x * prev.x + prev.y * prev.y + prev.z * prev.z)
        // bất thường nếu đổi vận tốc > 1.5 blocks/tick (rất dữ dội)
        return kotlin.math.abs(lastMag - prevMag) > 1.5
    }

    // --- Internal / helper utils ---

    /**
     * Gắn listener packet: khi nhận WorldTimeUpdate, cập nhật TPS.
     * NOTE: cách gọi listener phụ thuộc vào mod loader / event system bạn dùng.
     * Bạn chỉ cần gọi MovementAnalyzer.onTimeUpdatePacketReceived() khi nhận packet.
     */
    fun onTimeUpdatePacketReceived() {
        val currentTime = System.currentTimeMillis()
        if (lastTimeUpdate != -1L) {
            val elapsedSeconds = (currentTime - lastTimeUpdate) / 1000.0
            timeUpdatePackets++
            timeUpdateAccumulatedSeconds += elapsedSeconds
            if (timeUpdatePackets >= 20) {
                if (timeUpdateAccumulatedSeconds > 0.0) {
                    serverTPS = (20.0 / timeUpdateAccumulatedSeconds).coerceIn(0.0, 20.0)
                }
                timeUpdatePackets = 0
                timeUpdateAccumulatedSeconds = 0.0
            }
        }
        lastTimeUpdate = currentTime
    }

    // --- Low-level accessors ---
    // Những hàm này giả định entity có những thuộc tính/method tương ứng trong project bạn.
    // Nếu trong repo tên khác (ví dụ getPos()), sửa lại cho khớp.

    private fun getEntityPos(entity: Entity): Vec3d {
        // Tùy version: entity.pos hoặc entity.getPos()
        return try {
            // try property .pos first
            val posField = Entity::class.java.getDeclaredField("pos")
            posField.isAccessible = true
            posField.get(entity) as? Vec3d ?: entity.pos
        } catch (t: Throwable) {
            // fallback to existing API
            try {
                entity.pos
            } catch (e: Throwable) {
                Vec3d(0.0, 0.0, 0.0)
            }
        }
    }

    private fun getEntityVelocity(entity: Entity): Vec3d {
        // Tùy version: entity.velocity hoặc method
        return try {
            val velField = Entity::class.java.getDeclaredField("velocity")
            velField.isAccessible = true
            velField.get(entity) as? Vec3d ?: entity.velocity
        } catch (t: Throwable) {
            try {
                entity.velocity
            } catch (e: Throwable) {
                Vec3d(0.0, 0.0, 0.0)
            }
        }
    }
}
