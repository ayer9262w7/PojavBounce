package net.ccbluex.liquidbounce.features.module.modules.combat.killaura

// NetworkMonitor.kt
// Ghi nhận ping samples; ước lượng ping dự đoán (EMA), đo packet loss cơ bản (keepalive), và hệ thống pending-predictions

import net.minecraft.util.math.Vec3d
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.sqrt

object NetworkMonitor {
    // --- Ping history (ms) ---
    private val pingHistory = ArrayDeque<Long>()
    private const val PING_HISTORY_SIZE = 200

    // --- Keepalive counters cho packet loss estimation ---
    private var sentKeepalives: Long = 0
    private var acksReceived: Long = 0

    // --- Prediction backtest storage ---
    private val predictionErrors = ArrayDeque<Double>()
    private const val PREDICTION_ERROR_HISTORY = 120
    private var clientTickCounter: Long = 0

    data class PendingPrediction(
        val entityId: Int,
        val predictedPos: Vec3d,
        val ticksAhead: Int,
        val createdTick: Long
    )

    private val pendingPredictions = mutableListOf<PendingPrediction>()

    // --- Public API ---

    /**
     * Gọi khi có sample ping (ms). Hãy đảm bảo feed latency thực từ network handler.
     */
    fun recordPing(pingMs: Long) {
        synchronized(pingHistory) {
            pingHistory.addLast(pingMs)
            if (pingHistory.size > PING_HISTORY_SIZE) pingHistory.removeFirst()
        }
    }

    /**
     * Gọi khi client gửi keepalive (nếu bạn hook được).
     */
    fun recordSentKeepalive() {
        sentKeepalives++
    }

    /**
     * Gọi khi client nhận ack keepalive (nếu có).
     */
    fun recordAckReceived() {
        acksReceived++
    }

    /**
     * Trả estimated packet loss 0.0..1.0
     */
    fun getPacketLoss(): Double {
        if (sentKeepalives == 0L) return 0.0
        val loss = 1.0 - (acksReceived.toDouble() / sentKeepalives.toDouble())
        return loss.coerceIn(0.0, 1.0)
    }

    /**
     * Đăng ký prediction để backtest later.
     * ModuleKillAura nên gọi registerPrediction(...) mỗi khi dự đoán vị trí.
     */
    fun registerPrediction(entityId: Int, predictedPos: Vec3d, ticksAhead: Int) {
        pendingPredictions.add(PendingPrediction(entityId, predictedPos, ticksAhead, clientTickCounter))
    }

    /**
     * Gọi mỗi client tick (ModuleKillAura.onTick hoặc chung tick handler).
     * Dùng để đánh giá pending predictions khi đủ age.
     */
    fun onClientTick() {
        clientTickCounter++
        val toEval = mutableListOf<PendingPrediction>()
        val iterator = pendingPredictions.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            val age = clientTickCounter - p.createdTick
            if (age >= p.ticksAhead) {
                toEval.add(p)
                iterator.remove()
            }
        }
        for (p in toEval) {
            val actual = MovementAnalyzer.getLatestPosition(p.entityId)
            if (actual != null) {
                val err = actual.distanceTo(p.predictedPos)
                recordPredictionError(err)
            } else {
                // nếu không có actual, tạm phạt nhẹ
                recordPredictionError(2.0)
            }
        }
    }

    /**
     * Return prediction accuracy score 0..1 (1 = perfect).
     */
    fun getPredictionAccuracyScore(): Double {
        synchronized(predictionErrors) {
            if (predictionErrors.isEmpty()) return 1.0
            val mean = predictionErrors.average()
            val threshold = 4.0 // mean error 4 blocks => score 0
            return (1.0 - (mean / threshold)).coerceIn(0.0, 1.0)
        }
    }

    // --- Helpers ---

    private fun recordPredictionError(errMeters: Double) {
        synchronized(predictionErrors) {
            predictionErrors.addLast(errMeters)
            if (predictionErrors.size > PREDICTION_ERROR_HISTORY) predictionErrors.removeFirst()
        }
    }

    /**
     * Trả predicted ping (ms) bằng EMA smoothing trên lịch sử ping.
     */
    fun getPredictedPingMs(): Double {
        synchronized(pingHistory) {
            if (pingHistory.isEmpty()) return 50.0
            val alpha = 0.2
            var ema = pingHistory.first().toDouble()
            for (p in pingHistory) {
                ema = alpha * p + (1 - alpha) * ema
            }
            return ema
        }
    }

    /**
     * Alias / compatibility helpers (ModuleKillAura gọi các tên này trước đây).
     */
    fun getSmoothedPing(): Double = getPredictedPingMs()
    fun getFinalPingDecision(): Double = getPredictedPingMs()

    /**
     * Jitter estimate (standard deviation of ping samples).
     */
    fun getJitter(): Double {
        synchronized(pingHistory) {
            if (pingHistory.size < 2) return 0.0
            val mean = pingHistory.average()
            val variance = pingHistory.fold(0.0) { acc, v ->
                val d = v - mean
                acc + d * d
            } / pingHistory.size.toDouble()
            return sqrt(variance)
        }
    }
}
