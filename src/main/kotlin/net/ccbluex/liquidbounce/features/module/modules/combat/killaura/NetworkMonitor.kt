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
 *
 * @author Gemini & The Tester
 * @version 1.0 (August 2025)
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
    // TODO: Cần có cơ chế lưu lại các dự đoán và so sánh để tính điểm chính xác
    // private val predictionHistory = ...

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
        if (pingHistory.isEmpty()) return 50.0 // Trả về một giá trị mặc định an toàn
        return pingHistory.average()
    }

    /**
     * Tính toán độ bất ổn của mạng (Jitter).
     */
    fun getJitter(): Double {
        val n = pingHistory.size
        if (n < 2) return 0.0
        val mean = getSmoothedPing()
        val variance = pingHistory.sumOf { (it - mean) * (it - mean) } / n
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

        val m = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
        val b = (sumY - m * sumX) / n
        
        return m * (n + 1) + b
    }
    
    // --- Các hàm Placeholder (Cần được lập trình viên hiện thực hóa) ---

    /**
     * Placeholder: Lấy tỷ lệ mất gói tin hiện tại.
     * @return Tỷ lệ từ 0.0 (0%) đến 1.0 (100%).
     */
    private fun getPacketLoss(): Double {
        // TODO: Lập trình viên cần hiện thực hóa logic đo packet loss ở đây.
        return 0.0 
    }

    /**
     * Placeholder: Lấy Tick Rate của server.
     * @return Số tick mỗi giây của server.
     */
    private fun getServerTPS(): Double {
        // TODO: Lập trình viên cần hiện thực hóa logic lấy TPS từ server.
        return 20.0
    }

    /**
     * Placeholder: Lấy điểm chính xác của mô hình dự đoán.
     * @return Tỷ lệ từ 0.0 (0%) đến 1.0 (100%).
     */
    private fun getPredictionAccuracyScore(): Double {
        // TODO: Lập trình viên cần hiện thực hóa logic "backtest" ở đây.
        return 1.0
    }

    // --- Hàm Quyết định Cuối cùng ---
    
    /**
     * Hàm chính mà KillAura sẽ gọi.
     * Nó áp dụng tất cả các lớp logic để đưa ra giá trị ping tối ưu nhất cho việc dự đoán.
     * @return Giá trị ping cuối cùng để sử dụng.
     */
    fun getFinalPingDecision(): Double {
        // 1. Kiểm tra các quy tắc "Ngắt Mạch" ưu tiên cao nhất
        if (getPacketLoss() > PACKET_LOSS_THRESHOLD || 
            getServerTPS() < TPS_THRESHOLD ||
            getPredictionAccuracyScore() < PREDICTION_ACCURACY_THRESHOLD) {
            return getSmoothedPing() // Trở về chế độ an toàn tuyệt đối
        }

        // 2. Nếu an toàn, sử dụng mô hình lai tinh vi
        val smoothed = getSmoothedPing()
        val predicted = predictNextPing()
        val jitter = getJitter()
        val accuracy = getPredictionAccuracyScore()
        
        // Tính "Hệ số bất ổn" dựa trên cả jitter và độ chính xác của dự đoán
        val jitterFactor = max(0.0, min(1.0, (jitter - MIN_JITTER) / (MAX_JITTER - MIN_JITTER)))
        val accuracyFactor = 1.0 - accuracy // Độ chính xác càng thấp, hệ số này càng cao

        // Trọng số cuối cùng, kết hợp cả hai yếu tố
        val finalInstabilityFactor = max(jitterFactor, accuracyFactor)

        // Trộn hai giá trị theo tỷ lệ cuối cùng
        return (predicted * (1.0 - finalInstabilityFactor)) + (smoothed * finalInstabilityFactor)
    }
}