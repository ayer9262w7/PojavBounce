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

        MovementAnalyzer.recordEntityState(target)
        MovementAnalyzer.recordEntityState(player)

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

        // Gọi positional để đảm bảo đúng overload và tránh nhầm lẫn named-args giữa hai overloads
        val isFacingEnemy = facingEnemy(player, target, rotation, effectiveReach, wallRange.toDouble())
            || ModuleElytraTarget.canIgnoreKillAuraRotations

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
     * HÀM MỚI: Tính toán tầm đánh hiệu quả với dự đoán chuyển động
     */
    private fun calculateEffectiveReach(target: Entity): Double {
        val pingMs = NetworkMonitor.getFinalPingDecision()
        val speed = MovementAnalyzer.getSpeedTowardsTarget(player, target)
        val confidence = MovementAnalyzer.getPredictionConfidence(target)

        val predictedDistance = speed * (pingMs / 1000.0)

        val aggressionFactor = predictionAggressiveness.coerceIn(0.0f, 2.0f)
        val confidenceFactor = when {
            aggressionFactor <= 1.0f -> {
                confidence * aggressionFactor
            }
            else -> {
                val extraAggression = aggressionFactor - 1.0f
                (confidence + (1.0 - confidence) * extraAggression).coerceIn(0.0, 1.0)
            }
        }
        val adjustedPredictedDistance = predictedDistance * confidenceFactor

        val shouldUsePrediction = when {
            NetworkMonitor.getJitter() > 50.0 -> false
            MovementAnalyzer.is