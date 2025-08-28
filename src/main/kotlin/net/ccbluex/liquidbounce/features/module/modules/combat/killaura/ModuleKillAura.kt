/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2025 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
@file:Suppress("WildcardImport")
package net.ccbluex.liquidbounce.features.module.modules.combat.killaura

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.acos

@Suppress("MagicNumber")
object ModuleKillAura : ClientModule("KillAura", Category.COMBAT) {

    // ===== AI ENHANCEMENTS =====
    data class CombatAIContext(
        val playerHealth: Float,
        val playerArmor: Float,
        val nearbyEnemies: List<LivingEntity>,
        val networkLatency: Long
    )

    data class MovementSnapshot(
        val pos: Vec3d,
        val velocity: Vec3d,
        val timestamp: Long
    )

    data class BehaviorProfile(
        val aggressiveness: Float,
        val movementStyle: MovementStyle,
        val preferredRange: Float
    ) {
        companion object {
            val DEFAULT = BehaviorProfile(0.5f, MovementStyle.NORMAL, 4.0f)
        }
    }

    enum class MovementStyle {
        NORMAL, AGGRESSIVE, DEFENSIVE, CIRCLE_STRAFE
    }

    class MovementHistoryTracker {
        private val entityHistory = mutableMapOf<String, MutableList<MovementSnapshot>>()
        private val maxHistorySize = 15

        fun updateEntityHistory(entity: LivingEntity) {
            val uuid = entity.uuidAsString
            val snapshot = MovementSnapshot(
                pos = entity.pos,
                velocity = entity.velocity,
                timestamp = System.currentTimeMillis()
            )
            entityHistory.computeIfAbsent(uuid) { mutableListOf() }.apply {
                add(snapshot)
                if (size > maxHistorySize) removeAt(0)
            }
        }
        fun getHistory(uuid: String): List<MovementSnapshot> = entityHistory[uuid] ?: emptyList()
    }

    object KillAuraLearningSystem {
        private val successRateByBehavior = mutableMapOf<Int, Pair<Int, Int>>()

        suspend fun learnFromResult(target: Entity, success: Boolean) {
            if (target !is PlayerEntity) return
            val profile = analyzePlayerBehavior(target)
            val profileHash = profile.hashCode()
            val (currentSuccesses, currentAttempts) = successRateByBehavior.getOrDefault(profileHash, Pair(0, 0))
            val newSuccesses = if (success) currentSuccesses + 1 else currentSuccesses
            val newAttempts = currentAttempts + 1
            successRateByBehavior[profileHash] = Pair(newSuccesses, newAttempts)
        }

        suspend fun analyzePlayerBehavior(player: PlayerEntity): BehaviorProfile {
            val history = movementTracker.getHistory(player.uuidAsString)
            if (history.size < 5) return BehaviorProfile.DEFAULT
            
            val avgDistance = history.map { it.pos.distanceTo(ModuleKillAura.player.pos) }.average()
            val turningRate = calculateTurningRate(history.map { it.pos })
            val closingRate = (history.first().pos.distanceTo(ModuleKillAura.player.pos) - history.last().pos.distanceTo(ModuleKillAura.player.pos)) / history.size

            val movementStyle = when {
                turningRate > 0.4f && avgDistance < 4.0 -> MovementStyle.CIRCLE_STRAFE
                closingRate > 0.1 -> MovementStyle.AGGRESSIVE
                closingRate < -0.1 -> MovementStyle.DEFENSIVE
                else -> MovementStyle.NORMAL
            }
            
            return BehaviorProfile(
                aggressiveness = (closingRate * 5).toFloat().coerceIn(0.0f, 1.0f),
                movementStyle = movementStyle,
                preferredRange = avgDistance.toFloat()
            )
        }

        suspend fun calculateAdaptiveThreatScore(enemy: LivingEntity, baseScore: Float): Float {
            if (enemy !is PlayerEntity) return baseScore
            val profile = analyzePlayerBehavior(enemy)
            val profileHash = profile.hashCode()
            val (successes, attempts) = successRateByBehavior.getOrDefault(profileHash, Pair(0, 5))
            if (attempts < 3) return baseScore
            
            val successRate = successes.toFloat() / attempts.toFloat()
            val bonus = (0.5f - successRate) * 0.3f
            return baseScore + bonus
        }

        private fun calculateTurningRate(positions: List<Vec3d>): Float {
            if (positions.size < 3) return 0f
            val angles = mutableListOf<Double>()
            for (i in 2 until positions.size) {
                val v1 = positions[i - 1].subtract(positions[i - 2])
                val v2 = positions[i].subtract(positions[i - 1])
                if (v1.length() > 0.01 && v2.length() > 0.01) {
                    val cosAngle = v1.normalize().dotProduct(v2.normalize())
                    angles.add(acos(cosAngle.coerceIn(-1.0, 1.0)))
                }
            }
            return if (angles.isNotEmpty()) (angles.average() / Math.PI).toFloat() else 0f
        }
    }

    object KillAuraAIPredictor {
        suspend fun getOptimalTarget(context: CombatAIContext): LivingEntity? {
            if (context.nearbyEnemies.isEmpty()) return null
            
            val targetScores = context.nearbyEnemies.map { enemy ->
                val baseScore = calculateBaseThreatScore(enemy)
                val adaptiveScore = KillAuraLearningSystem.calculateAdaptiveThreatScore(enemy, baseScore)
                enemy to adaptiveScore
            }.sortedByDescending { it.second }

            return targetScores.firstOrNull()?.first
        }

        private fun calculateBaseThreatScore(enemy: LivingEntity): Float {
            val distance = ModuleKillAura.player.distanceTo(enemy)
            val distanceScore = (8f - distance.coerceIn(0f, 8f)) / 8f * 0.4f
            val healthScore = (1f - (enemy.health / enemy.maxHealth)) * 0.3f
            val armorScore = (20f - enemy.armor) / 20f * 0.1f
            val isApproaching = isEntityApproaching(enemy)
            val behaviorScore = if (isApproaching) 0.2f else 0.0f
            return distanceScore + healthScore + armorScore + behaviorScore
        }

        suspend fun predictTargetPosition(target: Entity, ticksAhead: Int): Vec3d {
            val history = ModuleKillAura.movementTracker.getHistory(target.uuidAsString)
            if (history.size < 3) {
                return target.pos.add(target.velocity.multiply(ticksAhead.toDouble()))
            }
            
            val lastMove = history.last().pos.subtract(history[history.size - 2].pos)
            val extrapolatedPos = target.pos.add(lastMove.multiply(ticksAhead.toDouble()))
            
            // Simple gravity compensation
            return if (!target.isOnGround) {
                extrapolatedPos.add(0.0, -0.04 * ticksAhead * ticksAhead, 0.0)
            } else {
                extrapolatedPos
            }
        }

        private fun isEntityApproaching(entity: Entity): Boolean {
            val directionToPlayer = ModuleKillAura.player.pos.subtract(entity.pos).normalize()
            val entityVelocity = entity.velocity.normalize()
            return directionToPlayer.dotProduct(entityVelocity) > 0.5
        }
    }

    val movementTracker = MovementHistoryTracker()
    private val learningEnabled by boolean("LearningEnabled", true)
    private val aiScope = CoroutineScope(Job() + Dispatchers.Default)
    // =============================

    val clickScheduler = tree(KillAuraClicker)
    
    internal val range by float("Range", 4.2f, 1f..8f)
    internal val wallRange by float("WallRange", 3f, 0f..8f).onChange { newValue ->
        // Return a Float (the possibly-clamped value). onChange expects to return the effective value.
        if (newValue > range) range else newValue
    }

    val samePlayer by boolean("SamePlayer", false)
    private val samePlayerDuration by int("SamePlayerDuration", 5, 1..120, "s")

    private val scanExtraRange by floatRange("ScanExtraRange", 2.0f..3.0f, 0.0f..7.0f).onChanged { r ->
        currentScanExtraRange = r.random()
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
        aiScope.cancel()
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

        // Cập nhật lịch sử di chuyển cho tất cả entities
        world.entities.filterIsInstance<LivingEntity>().forEach { entity ->
            movementTracker.updateEntityHistory(entity)
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
                    // tickHandler receiver is Sequence, call waitTicks on it
                    this.waitTicks(KillAuraAutoBlock.currentTickOff)
                }
                dealWithFakeSwing(this, null)
            }
            return@tickHandler
        }

        if (!requirementsMet) {
            return@tickHandler
        }

        val rotation = (if (rotations.rotationTiming == ON_TICK) {
            getSpot(target, range.toDouble(), PointTracker.AimSituation.FOR_NOW)?.rotation
        } else {
            null
        } ?: RotationManager.currentRotation ?: player.rotation).normalize()

        // Sử dụng AI để dự đoán vị trí và tối ưu hóa tấn công
        val aiContext = createAIContext()
        val aiOptimalTarget = if (learningEnabled) {
            try {
                KillAuraAIPredictor.getOptimalTarget(aiContext)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        
        val finalTarget = aiOptimalTarget ?: target

        // ===== BẮT ĐẦU ĐOẠN CODE CẢI TIẾN =====
        val crosshairTarget = if (samePlayer && targetTracker.stickyTarget != null) {
            // Nếu SamePlayer đang bật và có mục tiêu ghim, luôn dùng mục tiêu đó làm mục tiêu cuối cùng.
            targetTracker.stickyTarget
        } else {
            // Nếu không, mới thực hiện logic raycast và tìm mục tiêu dưới con trỏ chuột như cũ.
            val raycastTarget = when {
                raycast != TRACE_NONE -> {
                    raytraceEntity(range.toDouble(), rotation, filter = {
                        when (raycast) {
                            TRACE_ONLYENEMY -> it.shouldBeAttacked()
                            TRACE_ALL -> true
                            else -> false
                        }
                    })?.entity ?: finalTarget
                }
                else -> finalTarget
            }

            if (raycastTarget is LivingEntity && raycastTarget.shouldBeAttacked() && raycastTarget != finalTarget) {
                raycastTarget
            } else {
                finalTarget
            }
        }

        // Cập nhật target tracker nếu cần
        if (crosshairTarget != targetTracker.target && crosshairTarget is LivingEntity) {
            targetTracker.target = crosshairTarget
        }

        // Chỉ thực hiện tấn công nếu có một mục tiêu hợp lệ cuối cùng.
        if (crosshairTarget != null) {
            attackTarget(this, crosshairTarget, rotation)
        }
        // ===== KẾT THÚC ĐOẠN CODE CẢI TIẾN =====
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

    @Suppress("CognitiveComplexity", "CyclomaticComplexMethod")
    private suspend fun attackTarget(sequence: Sequence, target: Entity, rotation: Rotation) {
        KillAuraAutoBlock.makeSeemBlock()

        val isFacingEnemy = facingEnemy(toEntity = target, rotation = rotation,
            range = range.toDouble(),
            wallsRange = wallRange.toDouble()) || ModuleElytraTarget.canIgnoreKillAuraRotations

        ModuleDebug.debugParameter(ModuleKillAura, "Is Facing Enemy", isFacingEnemy)
        ModuleDebug.debugParameter(ModuleKillAura, "Rotation", rotation)
        ModuleDebug.debugParameter(ModuleKillAura, "Target", target.nameForScoreboard)

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

                val success = target.attack(true, keepSprint && !shouldBlockSprinting)

                // AI học từ kết quả (gọi trong coroutine)
                if (learningEnabled && success) {
                    aiScope.launch {
                        try {
                            KillAuraLearningSystem.learnFromResult(target, success)
                        } catch (e: Exception) {
                            // Ignore learning errors
                        }
                    }
                }

                if (samePlayer && target is LivingEntity) {
                    targetTracker.stickyTarget = target
                    targetTracker.stickyTimer.reset()
                }

                currentScanExtraRange = scanExtraRange.random()
                KillAuraNotifyWhenFail.failedHitsIncrement = 0

                GenericDebugRecorder.recordDebugInfo(ModuleKillAura, "attackEntity", JsonObject().apply {
                    add("player", GenericDebugRecorder.debugObject(player))
                    add("targetPos", GenericDebugRecorder.debugObject(target))
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

    private fun createAIContext(): CombatAIContext {
        return CombatAIContext(
            playerHealth = player.health,
            playerArmor = player.armor.toFloat(),
            nearbyEnemies = targetTracker.targets().filterIsInstance<LivingEntity>(),
            networkLatency = 50L // Placeholder
        )
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

    /**
     * HÀM MỚI ĐƯỢC THÊM VÀO ĐỂ SỬA LỖI XOAY GÓC
     * Tính toán đường đi góc quay ngắn nhất từ góc hiện tại đến góc mục tiêu.
     * Nó xử lý vấn đề "xoay vòng" khi đi qua ranh giới -180/180 độ.
     */
    private fun getShortestAngle(current: Float, target: Float): Float {
        var delta = (target - current) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return current + delta
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

        // LUÔN áp dụng hiệu chỉnh góc ngắn nhất để xoay mượt hơn
        val currentRotation = RotationManager.serverRotation
        val correctedRotation = Rotation(
            getShortestAngle(currentRotation.yaw, rotation.yaw),
            rotation.pitch
        )

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