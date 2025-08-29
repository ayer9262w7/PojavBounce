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
 * Bản vá: giữ declarations của delegate (float / floatRange) nhưng khi cần lấy min/ max
 * sẽ dùng helper extractNumericInterval / extractNumericValue để trả về Pair<Float,Float> hoặc Float,
 * tránh việc compiler suy ra kiểu chung không mong muốn khi kết hợp các delegate khác kiểu.
 *
 * Mục tiêu: vẫn giữ khả năng cấu hình (config tree) trong UI nhưng biên dịch an toàn.
 */
class HumanHybridAngleSmooth(parent: ChoiceConfigurable<*>) : AngleSmooth("HumanHybrid", parent) {

    // --- Config delegates (Sửa lỗi: Bỏ 'by', gán trực tiếp đối tượng config) ---
    private val baseYawAccel = floatRange("BaseYawAccel", 14f..22f, 1f..180f)
    private val basePitchAccel = floatRange("BasePitchAccel", 12f..20f, 1f..180f)

    private inner class Dynamic : ToggleableConfigurable(this, "Dynamic", true) {
        val distanceCoef = float("DistanceCoef", -1.0f, -5f..5f)
        val crosshairBoost = floatRange("CrosshairBoost", 16f..22f, 1f..180f)
    }

    private val dynamic = tree(Dynamic())

    // Prediction and jitter defaults (if delegate values cannot be resolved)
    private val predictionStrengthDefault = 0.25f
    private val humanJitterDefault = 0.6f
    private val maxStepDefault = 10f

    // EMA internal state (kept)
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
        // prediction offsets
        val predictionStrength = predictionStrengthDefault
        val predYawOffset = diff.deltaYaw * predictionStrength
        val predPitchOffset = diff.deltaPitch * predictionStrength

        val humanJitter = humanJitterDefault
        val maxStep = maxStepDefault

        // Sửa lỗi: Gọi hàm từ Companion object để tránh xung đột tên
        val distCoefSingle = try {
            Companion.extractNumericValue(dynamic.distanceCoef, -1.0f)
        } catch (_: Throwable) {
            -1.0f
        }
        
        // Cải tiến: Xử lý các trường hợp distance là NaN hoặc Infinity để tránh lỗi runtime
        val distCoef = if (distance.isFinite()) {
            (distCoefSingle * distance).toFloat()
        } else {
            0f // Giá trị mặc định an toàn
        }

        // Determine yaw interval numeric pair (lo, hi) using delegates but extracted safely
        val baseYawFallbackLo = 14f
        val baseYawFallbackHi = 22f
        val basePitchFallbackLo = 12f
        val basePitchFallbackHi = 20f
        val crosshairFallbackLo = 16f
        val crosshairFallbackHi = 22f

        // Sửa lỗi: Gọi hàm từ Companion object để tránh xung đột tên
        val yawInterval = if (dynamic.enabled && crosshair) {
            Companion.extractNumericInterval(dynamic.crosshairBoost, crosshairFallbackLo, crosshairFallbackHi)
        } else {
            Companion.extractNumericInterval(baseYawAccel, baseYawFallbackLo, baseYawFallbackHi)
        }
        val pitchInterval = Companion.extractNumericInterval(basePitchAccel, basePitchFallbackLo, basePitchFallbackHi)

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

        // clamp per-tick using coerceIn(min, max)
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

    companion object {
        // Helper: trả Pair(lo, hi) từ nhiều kiểu delegate/object khác nhau
        // Nếu không thể trích xuất sẽ trả fallbackLo..fallbackHi
        private fun extractNumericInterval(value: Any?, fallbackLo: Float, fallbackHi: Float): Pair<Float, Float> {
            if (value == null) return Pair(fallbackLo, fallbackHi)

            // 1) If Number -> single value
            if (value is Number) {
                val v = value.toFloat()
                return Pair(min(v, v), max(v, v))
            }

            try {
                val clazz = value.javaClass

                // try common range properties: start / endInclusive / end / to
                val startCandidate = try { clazz.getMethod("getStart").invoke(value) } catch (_: Throwable) { null }
                    ?: try { clazz.getMethod("start").invoke(value) } catch (_: Throwable) { null }
                    ?: try { clazz.getField("start").get(value) } catch (_: Throwable) { null }

                val endCandidate = try { clazz.getMethod("getEndInclusive").invoke(value) } catch (_: Throwable) { null }
                    ?: try { clazz.getMethod("endInclusive").invoke(value) } catch (_: Throwable) { null }
                    ?: try { clazz.getField("endInclusive").get(value) } catch (_: Throwable) { null }
                    ?: try { clazz.getMethod("getEnd").invoke(value) } catch (_: Throwable) { null }
                    ?: try { clazz.getMethod("end").invoke(value) } catch (_: Throwable) { null }
                    ?: try { clazz.getField("end").get(value) } catch (_: Throwable) { null }
                    ?: try { clazz.getMethod("getTo").invoke(value) } catch (_: Throwable) { null }
                    ?: try { clazz.getField("to").get(value) } catch (_: Throwable) { null }

                val startNum = startCandidate as? Number
                val endNum = endCandidate as? Number
                if (startNum != null && endNum != null) {
                    val lo = startNum.toFloat()
                    val hi = endNum.toFloat()
                    return Pair(min(lo, hi), max(lo, hi))
                }

                // try names min/max/from/to/first/last/value
                val tryNames = listOf(
                    "min", "max", "minimum", "maximum",
                    "lower", "upper",
                    "from", "to",
                    "first", "last",
                    "value", "v"
                )
                var foundLo: Float? = null
                var foundHi: Float? = null
                for (ln in tryNames) {
                    if (foundLo == null) {
                        val f = try { clazz.getMethod(ln).invoke(value) } catch (_: Throwable) { null }
                            ?: try { clazz.getMethod("get${ln.replaceFirstChar { it.uppercaseChar() }}").invoke(value) } catch (_: Throwable) { null }
                            ?: try { clazz.getField(ln).get(value) } catch (_: Throwable) { null }
                        if (f is Number) foundLo = f.toFloat()
                    }
                    if (foundHi == null) {
                        val h = try { clazz.getMethod(ln).invoke(value) } catch (_: Throwable) { null }
                            ?: try { clazz.getMethod("get${ln.replaceFirstChar { it.uppercaseChar() }}").invoke(value) } catch (_: Throwable) { null }
                            ?: try { clazz.getField(ln).get(value) } catch (_: Throwable) { null }
                        if (h is Number) foundHi = h.toFloat()
                    }
                    if (foundLo != null && foundHi != null) break
                }
                if (foundLo != null && foundHi != null) {
                    return Pair(min(foundLo, foundHi), max(foundLo, foundHi))
                }
            } catch (_: Throwable) {
                // ignore and fallback to parsing string form
            }

            // Fallback: parse numbers from toString()
            val s = value.toString()
            val regex = """-?\d+(?:\.\d+)?""".toRegex()
            val nums = regex.findAll(s).mapNotNull { it.value.toFloatOrNull() }.toList()
            return when {
                nums.size >= 2 -> {
                    val lo = min(nums[0], nums[1])
                    val hi = max(nums[0], nums[1])
                    Pair(lo, hi)
                }
                nums.size == 1 -> Pair(nums[0], nums[0])
                else -> Pair(fallbackLo, fallbackHi)
            }
        }

        // Helper: trả 1 Float từ nhiều kiểu (Number, delegated float, etc.), fallback nếu không có
        private fun extractNumericValue(value: Any?, fallback: Float): Float {
            if (value == null) return fallback
            if (value is Number) return value.toFloat()
            try {
                val clazz = value.javaClass
                val fieldNames = listOf("value", "v", "min", "max", "from", "to", "start", "end", "endInclusive")
                for (n in fieldNames) {
                    val candidate = try { clazz.getMethod(n).invoke(value) } catch (_: Throwable) { null }
                        ?: try { clazz.getMethod("get${n.replaceFirstChar { it.uppercaseChar() }}").invoke(value) } catch (_: Throwable) { null }
                        ?: try { clazz.getField(n).get(value) } catch (_: Throwable) { null }
                    if (candidate is Number) return candidate.toFloat()
                }
            } catch (_: Throwable) {
                // ignore
            }
            // fallback to parse
            val s = value.toString()
            val first = """-?\d+(?:\.\d+)?""".toRegex().find(s)?.value
            return first?.toFloatOrNull() ?: fallback
        }
    }
}