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
package net.ccbluex.liquidbounce.features.module.modules.combat.killaura

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.config.types.nesting.Configurable
import net.ccbluex.liquidbounce.utils.aiming.RotationsConfigurable

object KillAuraRotationsConfigurable : RotationsConfigurable(ModuleKillAura, combatSpecific = true) {

    val rotationTiming by enumChoice("RotationTiming", KillAuraRotationTiming.NORMAL)
    val aimThroughWalls by boolean("ThroughWalls", false)
    val aimingMode by enumChoice("AimingMode", AimingMode.ACCELERATION)

    // Nhóm các setting của Acceleration vào một Configurable riêng
    object AccelerationSettings : Configurable("Acceleration Settings") {
        val yawAcceleration by float("YawAcceleration", 0.18f, 0.1f..1.5f)
        val pitchAcceleration by float("PitchAcceleration", 0.25f, 0.1f..1.5f)
        val dampingFactor by float("DampingFactor", 0.75f, 0.1f..0.95f)
        val maxVelocity by float("MaxVelocity", 25.0f, 1.0f..100.0f)
    }

    // Khởi tạo nhóm setting
    init {
        tree(AccelerationSettings)
    }

    val smoothSpeed by float("SmoothSpeed", 0.15f, 0.05f..0.5f)

    enum class KillAuraRotationTiming(override val choiceName: String) : NamedChoice {
        NORMAL("Normal"),
        SNAP("Snap"),
        ON_TICK("OnTick")
    }

    enum class AimingMode(override val choiceName: String) : NamedChoice {
        LINEAR("Linear"),
        SIGMOID("Sigmoid"),
        INTERPOLATION("Interpolation"),
        ACCELERATION("Acceleration"),
        MINARAI("Minarai")
    }
}
