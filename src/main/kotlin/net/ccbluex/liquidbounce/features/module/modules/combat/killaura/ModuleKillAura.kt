@file:Suppress("WildcardImport")
package net.ccbluex.liquidbounce.features.module.modules.combat.killaura

import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.event.Sequence
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.SprintEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoWeapon
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals.CriticalsSelectionMode
import net.ccbluex.liquidbounce.features.module.modules.combat.elytratarget.ModuleElytraTarget
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraRotationsConfigurable.KillAuraRotationTiming.ON_TICK
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraRotationsConfigurable.KillAuraRotationTiming.SNAP
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura.RaycastMode.*
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraAutoBlock
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraFailSwing
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraFailSwing.dealWithFakeSwing
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraFightBot
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraNotifyWhenFail
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraNotifyWhenFail.failedHits
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraNotifyWhenFail.renderFailedHits
import net.ccbluex.liquidbounce.features.module.modules.misc.debugrecorder.modes.GenericDebugRecorder
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.data.RotationWithVector
import net.ccbluex.liquidbounce.utils.aiming.point.PointTracker
import net.ccbluex.liquidbounce.utils.aiming.preference.LeastDifferencePreference
import net.ccbluex.liquidbounce.utils.aiming.utils.facingEnemy
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBox
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceEntity
import net.ccbluex.liquidbounce.utils.combat.CombatManager
import net.ccbluex.liquidbounce.utils.combat.attack
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager.isInventoryOpen
import net.ccbluex.liquidbounce.utils.inventory.isInContainerScreen
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.ccbluex.liquidbounce.utils.render.WorldTargetRenderer
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.roundToInt

@Suppress("MagicNumber")
object ModuleKillAura : ClientModule("KillAura", Category.COMBAT) {

    val clickScheduler = tree(KillAuraClicker)

    internal val range by float("Range", 4.2f, 1f..8f)
    internal val wallRange by float("WallRange", 3f, 0f..8f).onChange { wallRange ->
        if (wallRange > range) {
            range
        } else {
            wallRange
        }
    }

    // --- THÊM CẤU HÌNH MỚI ---
    val predictionAggressiveness by float("PredictionAggressiveness", 1.0f, 0.0f..2.0f)
    val samePlayer by boolean("SamePlayer", false)
    private val samePlayerDuration by int("SamePlayerDuration", 5, 1..120, "s")

    private val scanExtraRange by floatRange("ScanExtraRange", 2.0f..3.0f, 0.0f..7.0f).onChanged { range ->
        currentScanExtraRange = range.random()
    }
    private var currentScanExtraRange: Float = scanExtraRange.random()

    val targetTracker = tree(KillAuraTargetTracker)
    private val rotations = tree(KillAuraRotationsConfigurable)
    private val pointTracker = tree(PointTracker())
    private val requires by multiEnumChoice<KillAuraRequirements>("Requires")
    private val requirementsMet
        get() = requires.all { it.meets() }

    internal val raycast by enumChoice("Raycast", TRACE_ALL)
    private val criticalsSelectionMode by enumChoice("Criticals", CriticalsSelectionMode.SMART)
    private val keepSprint by boolean("KeepSprint", true)

    internal val ignoreOpenInventory by boolean("IgnoreOpenInventory", true)
    internal val simulateInventoryClosing by boolean("SimulateInventoryClosing", true)

    init {
        tree(KillAuraAutoBlock)
    }

    private val targetRenderer = tree(WorldTargetRenderer(this))

    init {
        tree(KillAuraFailSwing)
        tree(KillAuraFightBot)
    }

    override fun disable() {
        targetTracker.reset()
        failedHits.clear()
        KillAuraAutoBlock.stopBlocking()
        KillAuraNotifyWhenFail.failedHitsIncrement = 0
        targetTracker.stickyTarget = null
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        val matrixStack = event.matrixStack
        renderTarget(matrixStack, event.partialTicks)
        renderFailedHits(matrixStack)
    }

    private fun renderTarget(matrixStack: MatrixStack, partialTicks: Float) {
        val target = targetTracker.target
            ?.takeIf { targetRenderer.enabled }
            ?.takeIf { !ModuleElytraTarget.isSameTargetRendering(it) }
            ?: return

        renderEnvironmentForWorld(matrixStack) {
            targetRenderer.render(this, target, partialTicks)
        }
    }

    @Suppress("unused")
    private val rotationUpdateHandler = handler<RotationUpdateEvent> {
        val isInInventoryScreen =
            InventoryManager.isInventoryOpen || mc.currentScreen is GenericContainerScreen

        val shouldResetTarget = player.isSpectator || player.isDead || !requirementsMet

        if (isInInventoryScreen && !ignoreOpenInventory || shouldResetTarget) {
            targetTracker.reset()
            targetTracker.stickyTarget = null
            return@handler
        }

        updateTarget()
        ModuleAutoWeapon.prepare(targetTracker.target)
    }

    @Suppress("unused")
    private val gameHandler = tickHandler {
        if (player.isDead || player.isSpectator) {
            return@tickHandler
        }

        val target = targetTracker.target

        if (CombatManager.shouldPauseCombat) {
            KillAuraAutoBlock.stopBlocking()
            return@tickHandler
        }

        if (target == null) {
            val hasUnblocked = KillAuraAutoBlock.stopBlocking()

            if (KillAuraFailSwing.enabled && requirementsMet) {
                if (hasUnblocked) {
                    waitTicks(KillAuraAutoBlock.currentTickOff)
                }
                dealWithFakeSwing(this, null)
            }
            return@tickHandler
        }

        if (!requirementsMet) {
            return@tickHandler
        }

        // --- CẬP NHẬT: GHI LẠI TRẠNG THÁI MỤC TIÊU CHO PHÂN TÍCH ---
        MovementAnalyzer.recordEntityState(target)
        MovementAnalyzer.recordEntityState(player)

        // === MỚI: feed NetworkMonitor ping sample (nếu có latency available) ===
        try {
            // API lấy player list entry có thể khác tuỳ phiên bản; giữ try/catch để an toàn.
            val handler = mc.networkHandler
            val entry = handler?.getPlayerListEntry(player.uuid)
            if (entry != null) {
                NetworkMonitor.recordPing(entry.latency.toLong())
            }
        } catch (_: Throwable) {
            // ignore nếu API khác
        }
        // =======================================================================

        val rotation = (if (rotations.rotationTiming == ON_TICK) {
            getSpot(target, range.toDouble(), PointTracker.AimSituation.FOR_NOW)?.rotation
        } else {
            null
        } ?: RotationManager.currentRotation ?: player.rotation).normalize()

        val finalTarget = if (samePlayer && targetTracker.stickyTarget != null) {
            targetTracker.stickyTarget
        } else {
            val crosshairTarget = when {
                raycast != TRACE_NONE -> {
                    raytraceEntity(range.toDouble(), rotation, filter = {
                        when (raycast) {
                            TRACE_ONLYENEMY -> it.shouldBeAttacked()
                            TRACE_ALL -> true
                            else -> false
                        }
                    })?.entity ?: target
                }
                else -> target
            }

            if (crosshairTarget is LivingEntity && crosshairTarget.shouldBeAttacked() && crosshairTarget != target) {
                targetTracker.target = crosshairTarget
            }

            crosshairTarget
        }

        if (finalTarget != null) {
            attackTarget(this, finalTarget, rotation)
        }
    }

    val shouldBlockSprinting
        get() = !ModuleElytraTarget.running
            && criticalsSelectionMode.shouldStopSprinting(clickScheduler, targetTracker.target)

    @Suppress("unused")
    private val sprintHandler = handler<SprintEvent> { event ->
        if (shouldBlockSprinting && (event.source == SprintEvent.Source.MOVEMENT_TICK ||
                event.source == SprintEvent.Source.INPUT)) {
            event.sprint = false
        }
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
    private suspend fun attackTarget(sequence: Sequence, target: Entity, rotation: Rotation) {
        KillAuraAutoBlock.makeSeemBlock()

        // --- TÍNH TOÁN TẦM ĐÁNH HIỆU QUẢ VỚI DỰ ĐOÁN ---
        val actualReach = range.toDouble()
        val effectiveReach = calculateEffectiveReach(target)

        // Gọi rõ ràng overload từ player eye -> tránh sự nhầm lẫn khi compiler chọn overload khác
        val isFacingEnemy = facingEnemy(
            fromEntity = player,
            toEntity = target,
            rotation = rotation,
            range = effectiveReach,
            wallsRange = wallRange.toDouble()
        ) || ModuleElytraTarget.canIgnoreKillAuraRotations

        ModuleDebug.debugParameter(ModuleKillAura, "Is Facing Enemy", isFacingEnemy)
        ModuleDebug.debugParameter(ModuleKillAura, "Rotation", rotation)
        ModuleDebug.debugParameter(ModuleKillAura, "Target", target.nameForScoreboard)
        ModuleDebug.debugParameter(ModuleKillAura, "Effective Reach", effectiveReach)

        if (!isFacingEnemy) {
            if (KillAuraAutoBlock.enabled && KillAuraAutoBlock.onScanRange &&
                player.squaredBoxedDistanceTo(target) <= (range + currentScanExtraRange).pow(2)) {
                KillAuraAutoBlock.startBlocking()
                return
            }

            val hasUnblocked = KillAuraAutoBlock.stopBlocking()

            if (KillAuraFailSwing.enabled) {
                if (hasUnblocked) {
                    sequence.waitTicks(KillAuraAutoBlock.currentTickOff)
                }

                dealWithFakeSwing(sequence, target)
            }
            return
        }

        ModuleDebug.debugParameter(ModuleKillAura, "Valid Rotation", rotation)

        if (clickScheduler.isClickTick && validateAttack(target)) {
            clickScheduler.attack(sequence, rotation) {
                if (!validateAttack(target)) {
                    return@attack false
                }

                target.attack(true, keepSprint && !shouldBlockSprinting)

                if (samePlayer && target is LivingEntity) {
                    targetTracker.stickyTarget = target
                    targetTracker.stickyTimer.reset()
                }

                currentScanExtraRange = scanExtraRange.random()
                KillAuraNotifyWhenFail.failedHitsIncrement = 0

                GenericDebugRecorder.recordDebugInfo(ModuleKillAura, "attackEntity", JsonObject().apply {
                    add("player", GenericDebugRecorder.debugObject(player))
                    add("targetPos", GenericDebugRecorder.debugObject(target))
                    add("effectiveReach", GenericDebugRecorder.debugObject(effectiveReach))
                })

                true
            }
        } else if (KillAuraAutoBlock.currentTickOff > 0 && clickScheduler.willClickAt(KillAuraAutoBlock.currentTickOff)
            && KillAuraAutoBlock.shouldUnblockToHit) {
            KillAuraAutoBlock.stopBlocking(pauses = true)
        } else {
            KillAuraAutoBlock.startBlocking()
        }
    }

    /**
     * HÀM ĐÃ VÁ (thay thế DUY NHẤT so với file gốc):
     * Tính toán tầm đánh hiệu quả với dự đoán chuyển động — giữ logic gốc nhưng thêm:
     * - clamp maximum extra reach,
     * - jitter humanize,
     * - ngắt mạch khi mạng kém / chuyển động bất thường,
     * - đăng ký prediction để NetworkMonitor có thể backtest (nếu method tồn tại).
     */
    private fun calculateEffectiveReach(target: Entity): Double {
        val actualReachVal = range.toDouble()

        // Lấy final ping quyết định từ NetworkMonitor (ms)
        val pingMs = try {
            NetworkMonitor.getFinalPingDecision()
        } catch (e: Throwable) {
            // fallback an toàn
            NetworkMonitor.getSmoothedPing()
        }

        // Lấy tốc độ/dự đoán movement từ MovementAnalyzer (sử dụng hàm có sẵn nếu có)
        val predictedMovement = try {
            MovementAnalyzer.getPredictedMovementInPingTime(player, target, pingMs.roundToLong())
        } catch (e: Throwable) {
            // fallback: sử dụng simple speed * time
            val speed = MovementAnalyzer.calculateSpeedTowardsTarget(player, target)
            speed * (pingMs / 1000.0)
        }

        // Lấy confidence từ MovementAnalyzer
        val confidence = try {
            MovementAnalyzer.getPredictionConfidence(target)
        } catch (e: Throwable) {
            0.5
        }

        // aggression config
        val aggressionFactor = predictionAggressiveness.coerceIn(0.0f, 2.0f)
        val confidenceFactor = if (aggressionFactor <= 1.0f) {
            confidence * aggressionFactor
        } else {
            val extra = (aggressionFactor - 1.0f)
            (confidence + (1.0 - confidence) * extra).coerceIn(0.0, 1.0)
        }

        var adjustedPredictedDistance = predictedMovement * confidenceFactor

        // safety checks: network / anomaly
        val jitter = try { NetworkMonitor.getJitter() } catch (_: Throwable) { 0.0 }
        val smoothedPing = try { NetworkMonitor.getSmoothedPing() } catch (_: Throwable) { 50.0 }

        val badNetwork = (jitter > 50.0) || (smoothedPing > 400.0)
        val anomalous = try { MovementAnalyzer.isMovementAnomalous(target) } catch (_: Throwable) { false }

        val shouldUsePrediction = when {
            badNetwork -> false
            anomalous -> false
            confidence < 0.25 && aggressionFactor < 1.5 -> false
            else -> true
        }

        // clamp extra reach
        val maxExtra = max(0.4, currentScanExtraRange.toDouble()) // at least 0.4 blocks, else use scanExtraRange
        val extra = if (shouldUsePrediction) adjustedPredictedDistance.coerceAtMost(maxExtra) else 0.0

        // humanize small jitter
        val humanJitter = (Math.random() - 0.5) * 0.06 // ±0.03 blocks
        val finalExtra = (extra + humanJitter).coerceIn(0.0, maxExtra)

        // Register prediction for backtest if NetworkMonitor supports it (use reflection safe)
        try {
            val pingTicks = max(1, ((pingMs / 1000.0) * 20.0).roundToInt())
            val predictedPos = try {
                MovementAnalyzer.predictFuturePosition(target, pingTicks)
            } catch (_: Throwable) {
                // fallback to simple extrapolation using current pos
                target.pos
            }

            // Try to find registerPrediction(EntityId, Vec3d, Int)
            val method = NetworkMonitor::class.java.methods.firstOrNull { it.name == "registerPrediction" }
            if (method != null) {
                method.invoke(NetworkMonitor, target.id, predictedPos, pingTicks)
            }
        } catch (_: Throwable) {
            // ignore if not supported
        }

        return actualReachVal + finalExtra
    }

    private fun updateTarget() {
        val situation = when {
            clickScheduler.isClickTick || clickScheduler.willClickAt(1)
                -> PointTracker.AimSituation.FOR_NEXT_TICK
            else -> PointTracker.AimSituation.FOR_THE_FUTURE
        }
        ModuleDebug.debugParameter(ModuleKillAura, "AimSituation", situation)

        if (samePlayer) {
            val sticky = targetTracker.stickyTarget
            if (sticky != null) {
                val timePassed = targetTracker.stickyTimer.hasTimePassed((samePlayerDuration * 1000).toLong())
                if (!sticky.isAlive || timePassed) {
                    targetTracker.stickyTarget = null
                } else {
                    if (player.squaredBoxedDistanceTo(sticky) <= range.pow(2) && targetTracker.validate(sticky)) {
                        targetTracker.target = sticky
                        processTarget(sticky, range, situation)
                        return
                    } else {
                        targetTracker.target = null
                        return
                    }
                }
            }
        }

        val maximumRange = if (targetTracker.closestSquaredEnemyDistance > range.pow(2)) {
            range + currentScanExtraRange
        } else {
            range
        }

        ModuleDebug.debugParameter(ModuleKillAura, "Maximum Range", maximumRange)
        ModuleDebug.debugParameter(ModuleKillAura, "Range", range)
        val squaredMaxRange = maximumRange.pow(2)
        val squaredNormalRange = range.pow(2)

        val target = targetTracker.targets()
            .filter { entity -> entity.squaredBoxedDistanceTo(player) <= squaredMaxRange }
            .sortedBy { entity -> if (entity.squaredBoxedDistanceTo(player) <= squaredNormalRange) 0 else 1 }
            .firstOrNull { entity -> processTarget(entity, maximumRange, situation) }

        if (target != null) {
            targetTracker.target = target
        } else if (KillAuraFightBot.enabled) {
            KillAuraFightBot.updateTarget()

            RotationManager.setRotationTarget(
                rotations.toRotationTarget(
                    KillAuraFightBot.getMovementRotation(),
                    considerInventory = !ignoreOpenInventory
                ),
                priority = Priority.IMPORTANT_FOR_USAGE_2,
                provider = ModuleKillAura
            )
        } else {
            targetTracker.reset()
        }
    }

    private fun getShortestAngle(currentYaw: Float, targetYaw: Float): Float {
        var delta = (targetYaw - currentYaw) % 360.0f
        if (delta >= 180.0f) {
            delta -= 360.0f
        }
        if (delta < -180.0f) {
            delta += 360.0f
        }
        return currentYaw + delta
    }

    @Suppress("ReturnCount")
    private fun processTarget(
        entity: LivingEntity,
        maximumRange: Float,
        situation: PointTracker.AimSituation
    ): Boolean {
        val (rotation, _) = getSpot(entity, maximumRange.toDouble(), situation) ?: return false
        val ticks = rotations.calculateTicks(rotation)
        ModuleDebug.debugParameter(ModuleKillAura, "Rotation Ticks", ticks)

        val currentYaw = RotationManager.serverRotation.yaw
        val shortestYaw = getShortestAngle(currentYaw, rotation.yaw)
        val correctedRotation = Rotation(shortestYaw, rotation.pitch)

        when (rotations.rotationTiming) {
            SNAP -> if (!clickScheduler.willClickAt(ticks.coerceAtLeast(1))) {
                return true
            }
            ON_TICK -> if (ticks <= 1) {
                return true
            }
            else -> {
            }
        }

        RotationManager.setRotationTarget(
            rotations.toRotationTarget(
                correctedRotation,
                entity,
                considerInventory = !ignoreOpenInventory
            ),
            priority = Priority.IMPORTANT_FOR_USAGE_2,
            provider = this@ModuleKillAura
        )

        return true
    }

    private fun getSpot(entity: LivingEntity, range: Double,
                        situation: PointTracker.AimSituation): RotationWithVector? {
        val point = pointTracker.gatherPoint(
            entity,
            situation
        )

        val eyes = point.fromPoint
        val nextPoint = point.toPoint

        ModuleDebug.debugGeometry(this, "Box",
            ModuleDebug.DebuggedBox(point.box, Color4b.RED.with(a = 60)))
        ModuleDebug.debugGeometry(this, "CutOffBox",
            ModuleDebug.DebuggedBox(point.cutOffBox, Color4b.GREEN.with(a = 90)))
        ModuleDebug.debugGeometry(this, "Point", ModuleDebug.DebuggedPoint(nextPoint, Color4b.WHITE))

        val rotationPreference = LeastDifferencePreference.leastDifferenceToLastPoint(eyes, nextPoint)

        val spot = raytraceBox(
            eyes, point.cutOffBox,
            range = range,
            wallsRange = wallRange.toDouble(),
            rotationPreference = rotationPreference
        ) ?: raytraceBox(
            eyes, point.box,
            range = range,
            wallsRange = wallRange.toDouble(),
            rotationPreference = rotationPreference
        )

        return if (spot == null && rotations.aimThroughWalls) {
            val throughSpot = raytraceBox(
                eyes, point.cutOffBox,
                range = range,
                wallsRange = range,
                rotationPreference = rotationPreference
            ) ?: raytraceBox(
                eyes, point.box,
                range = range,
                wallsRange = range,
                rotationPreference = rotationPreference
            )

            throughSpot
        } else {
            spot
        }
    }

    internal fun validateAttack(target: Entity? = null): Boolean {
        val criticalHit = target == null || player.isGliding || criticalsSelectionMode.isCriticalHit(target)
        val isInInventoryScreen = isInventoryOpen || isInContainerScreen

        return criticalHit && !(isInInventoryScreen && !ignoreOpenInventory && !simulateInventoryClosing)
    }

    enum class RaycastMode(override val choiceName: String) : NamedChoice {
        TRACE_NONE("None"),
        TRACE_ONLYENEMY("Enemy"),
        TRACE_ALL("All")
    }
}
