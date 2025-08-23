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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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

    val samePlayer by boolean("SamePlayer", false)
    private val samePlayerDuration by int("SamePlayerDuration", 5, 1..120, "s")

    // ===== CÁC SETTING MỚI CHO ACCELERATION =====
    private val yawAcceleration by float("YawAcceleration", 0.18f, 0.1f..0.5f)
    private val pitchAcceleration by float("PitchAcceleration", 0.25f, 0.1f..0.5f)
    private val dampingFactor by float("DampingFactor", 0.75f, 0.5f..0.95f)
    private val maxVelocity by float("MaxVelocity", 25.0f, 10.0f..40.0f)
    // ===============================================

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

    // ===== BIẾN TRẠNG THÁI MỚI CHO ACCELERATION =====
    private var currentYaw: Float = 0.0f
    private var yawVelocity: Float = 0.0f
    private var currentPitch: Float = 0.0f
    private var pitchVelocity: Float = 0.0f
    // =============================================

    init {
        tree(KillAuraAutoBlock)
    }

    private val targetRenderer = tree(WorldTargetRenderer(this))

    init {
        tree(KillAuraFailSwing)
        tree(KillAuraFightBot)
    }

    override fun enable() {
        // Khởi tạo góc quay hiện tại để tránh bị giật khi bật module
        if (mc.player != null) {
            currentYaw = mc.player!!.yaw
            currentPitch = mc.player!!.pitch
        }
        yawVelocity = 0.0f
        pitchVelocity = 0.0f
    }

    override fun disable() {
        targetTracker.reset()
        failedHits.clear()
        KillAuraAutoBlock.stopBlocking()
        KillAuraNotifyWhenFail.failedHitsIncrement = 0
        targetTracker.stickyTarget = null
        yawVelocity = 0.0f
        pitchVelocity = 0.0f
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
        
        // Logic rotation cũ sẽ bị ghi đè bởi logic gia tốc trong processTarget
        val rotation = RotationManager.currentRotation ?: player.rotation

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

    // ===== HÀM PROCESS TARGET ĐÃ ĐƯỢC VIẾT LẠI HOÀN TOÀN =====
    @Suppress("ReturnCount")
    private fun processTarget(
        entity: LivingEntity,
        maximumRange: Float,
        situation: PointTracker.AimSituation
    ): Boolean {
        // 1. Lấy góc quay lý tưởng đến mục tiêu
        val (idealTargetRotation, _) = getSpot(entity, maximumRange.toDouble(), situation) ?: return false

        // 2. Tính toán "lực" gia tốc dựa trên chênh lệch góc quay (có xử lý vòng tròn)
        val yawDifference = normalizeAngleDifference(idealTargetRotation.yaw - currentYaw)
        val pitchDifference = idealTargetRotation.pitch - currentPitch // Pitch không cần xử lý vòng tròn

        val yawForce = yawDifference * yawAcceleration
        val pitchForce = pitchDifference * pitchAcceleration

        // 3. Cập nhật vận tốc dựa trên lực
        yawVelocity += yawForce
        pitchVelocity += pitchForce

        // 4. Giới hạn vận tốc tối đa (Velocity Cap)
        yawVelocity = clamp(yawVelocity, -maxVelocity, maxVelocity)
        pitchVelocity = clamp(pitchVelocity, -maxVelocity, maxVelocity)

        // 5. Áp dụng lực cản/ma sát (Damping)
        yawVelocity *= dampingFactor
        pitchVelocity *= dampingFactor

        // 6. Cập nhật góc quay hiện tại dựa trên vận tốc
        currentYaw += yawVelocity
        currentPitch = clamp(currentPitch + pitchVelocity, -90.0f, 90.0f) // Giới hạn pitch trong khoảng -90/90

        // 7. Đặt góc quay cuối cùng cho game
        val finalRotation = Rotation(currentYaw, currentPitch)
        RotationManager.setRotationTarget(finalRotation, priority = Priority.IMPORTANT_FOR_USAGE_2, provider = this)

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

        return criticalHit && !(isInInventoryScreen && !ignoreOpenInventory && !simulateIn
