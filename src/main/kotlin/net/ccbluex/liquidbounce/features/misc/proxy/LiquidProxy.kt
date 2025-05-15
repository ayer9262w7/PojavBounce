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
package net.ccbluex.liquidbounce.features.misc.proxy

import net.ccbluex.liquidbounce.api.core.AsyncLazy
import net.ccbluex.liquidbounce.api.models.proxy.ProxySubscription
import net.ccbluex.liquidbounce.api.services.proxy.ProxyApi

/**
 * Liquid Proxy Integration
 * @website https://liquidproxy.net
 */
object LiquidProxy {

    /**
     * All available relay locations.
     *
     * These relay proxy servers will automatically forward the incoming SOCKS5 connection
     * through another IP (dedicated and residential) and to the target server.
     *
     * The relay servers match the IP based on the target server and the username of the joining player
     * in order to give out unique IPs for each player.
     */
    val locations by AsyncLazy(ProxyApi::getLocations)

    /**
     * Uses the [ProxyManager] to connect to a temporary created [Proxy]
     * with the given [level] and [location] for the given [subscription].
     */
    fun connect(subscription: ProxySubscription, level: Int, location: String) {
        // TODO: Replace the use of credentials with a token
        val username = "${subscription.credentials.username}.${level}"
        val password = subscription.credentials.password

        val host = "any.${location}.liquidproxy.net"
        val port = 1080 // LiquidProxy always uses port 1080

        val proxy = Proxy(host, port, Proxy.Credentials(
            username,
            password
        ), Proxy.Type.SOCKS5)

        ProxyManager.proxy = proxy
    }

}
