package net.ccbluex.liquidbounce.features.module.modules.combat.killaura

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * NetworkMonitor - Một bộ phân tích sức khỏe mạng toàn diện.
 *
 * Chức năng:
 * - Thu thập và làm mịn dữ liệu ping.
 * - Dự đoán giá trị ping tiếp theo dựa trên xu hướng.
 * - Tự động điều chỉnh dự đoán dựa trên độ ổn định của mạng (Jitter).
 * - Kết hợp các chỉ số khác (Packet Loss, Server TPS) để có quyết định cuối cùng.
 * - Áp dụng các quy tắc an toàn "ngắt mạch" để tránh bị Anti-Cheat phát hiện.
 */
object NetworkMonitor {

    // --- Cấu hình ---
    private const val HISTORY_SIZE = 20 // Số lượng giá trị ping để lưu lại

    // Ngưỡng cho mô hình lai
    private const val MIN_JITTER = 5.0  // Dưới ngưỡng này, mạng được coi là hoàn hảo
    private const val MAX_JITTER = 25.0 // Trên ngưỡng này, mạng được coi là rất bất ổn

    // Ngưỡng cho các quy tắc "Ngắt Mạch" an toàn
    private const val PACKET_LOSS_THRESHOLD = 0.05 // 5%
    private const val TPS_THRESHOLD = 15.0 // 15 Ticks Per Second
    private const val PREDICTION_ACCURACY_THRESHOLD = 0.7 // 70%

    // --- Bộ nhớ ---
    private val pingHistory = ArrayDeque<Long>(HISTORY_SIZE)

    // --- Các hàm cơ bản ---

    /**
     * Ghi lại một giá trị ping mới đo được.
     */
    fun recordPing(newPing: Long) {
        if (pingHistory.size >= HISTORY_SIZE) {
            pingHistory.removeFirst()
        }
        pingHistory.addLast(newPing)
    }

    /**
     * Lấy giá trị ping trung bình đã được làm mịn.
     */
    fun getSmoothedPing(): Double {
        if (pingHistory.isEmpty()) return 50.0 // giá trị mặc định an toàn
        return pingHistory.map { it.toDouble() }.average()
    }

    /**
     * Tính toán độ bất ổn của mạng (Jitter).
     */
    fun getJitter(): Double {
        val n = pingHistory.size
        if (n < 2) return 0.0
        val mean = getSmoothedPing()
        val variance = pingHistory.map { (it.toDouble() - mean) * (it.toDouble() - mean) }.average()
        return sqrt(variance)
    }

    // --- Các hàm dự đoán ---

    /**
     * Dự đoán giá trị ping tiếp theo bằng Hồi quy Tuyến tính.
     */
    private fun predictNextPing(): Double {
        val n = pingHistory.size
        if (n < 2) return getSmoothedPing()

        val points = pingHistory.toList()
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0

        for (i in 0 until n) {
            val x = (i + 1).toDouble()
            val y = points[i].toDouble()
            sumX += x
            sumY += y
            sumXY += x * y
            sumX2 += x * x
        }

        val denominator = (n * sumX2 - sumX * sumX)
        if (kotlin.math.abs(denominator) < 1e-9) return getSmoothedPing()

        val m = (n * sumXY - sumX * sumY) / denominator
        val b = (sumY - m * sumX) / n

        return m * (n + 1) + b
    }

    // --- Các hàm Placeholder (Cần được lập trình viên hiện thực hóa) ---

    private fun getPacketLoss(): Double {
        // TODO: hiện thực hóa logic đo packet loss ở đây.
        return 0.0
    }

    private fun getServerTPS(): Double {
        // TODO: hiện thực hóa logic lấy TPS từ server.
        return 20.0
    }

    private fun getPredictionAccuracyScore(): Double {
        // TODO: hiện thực hóa logic "backtest" ở đây.
        return 1.0
    }

    // --- Hàm Quyết định Cuối cùng ---

    /**
     * Hàm chính mà KillAura sẽ gọi.
     * Nó áp dụng các quy tắc và mô hình để trả về giá trị ping (ms) nên dùng.
     */
    fun getFinalPingDecision(): Double {
        // Các quy tắc ngắt mạch ưu tiên
        if (getPacketLoss() > PACKET_LOSS_THRESHOLD ||
            getServerTPS() < TPS_THRESHOLD ||
            getPredictionAccuracyScore() < PREDICTION_ACCURACY_THRESHOLD
        ) {
            return getSmoothedPing()
        }

        val smoothed = getSmoothedPing()
        val predicted = predictNextPing()
        val jitter = getJitter()
        val accuracy = getPredictionAccuracyScore()

        val jitterFactor = max(0.0, min(1.0, (jitter - MIN_JITTER) / (MAX_JITTER - MIN_JITTER)))
        val accuracyFactor = 1.0 - accuracy

        val finalInstabilityFactor = max(jitterFactor, accuracyFactor)

        return (predicted * (1.0 - finalInstabilityFactor)) + (smoothed * finalInstabilityFactor)
    }
}