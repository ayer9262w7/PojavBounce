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
 *
 */

@file:Suppress("TooManyFunctions")

package net.ccbluex.liquidbounce.integration.interop.protocol.rest.v1.client

import com.google.gson.JsonArray
import com.mojang.blaze3d.systems.RenderSystem
import io.netty.handler.codec.http.FullHttpResponse
import kotlinx.coroutines.runBlocking
import net.ccbluex.liquidbounce.api.models.auth.ClientAccount
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.gson.interopGson
import net.ccbluex.liquidbounce.config.gson.util.emptyJsonObject
import net.ccbluex.liquidbounce.features.cosmetic.ClientAccountManager
import net.ccbluex.liquidbounce.features.misc.proxy.LiquidProxy
import net.ccbluex.liquidbounce.features.misc.proxy.Proxy
import net.ccbluex.liquidbounce.features.misc.proxy.ProxyManager
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.netty.http.model.RequestObject
import net.ccbluex.netty.http.util.httpForbidden
import net.ccbluex.netty.http.util.httpOk
import org.lwjgl.glfw.GLFW

/**
 * Proxy endpoints
 */

// GET /api/v1/client/proxy
@Suppress("UNUSED_PARAMETER")
fun getProxyInfo(requestObject: RequestObject) = httpOk(ProxyManager.currentProxy?.let { proxy ->
    interopGson.toJsonTree(proxy).asJsonObject.apply {
        addProperty("id", ProxyManager.proxies.indexOf(proxy))
    }
} ?: emptyJsonObject())

// POST /api/v1/client/proxy
@Suppress("UNUSED_PARAMETER")
fun postProxy(requestObject: RequestObject): FullHttpResponse {
    data class ProxyRequest(val id: Int)

    val body = requestObject.asJson<ProxyRequest>()

    if (body.id < 0 || body.id >= ProxyManager.proxies.size) {
        return httpForbidden("Invalid id")
    }
    
    ProxyManager.proxy = ProxyManager.proxies[body.id]
    return httpOk(emptyJsonObject())
}

// DELETE /api/v1/client/proxy
@Suppress("UNUSED_PARAMETER")
fun deleteProxy(requestObject: RequestObject): FullHttpResponse {
    ProxyManager.proxy = Proxy.NONE
    return httpOk(emptyJsonObject())
}

// GET /api/v1/client/proxies
@Suppress("UNUSED_PARAMETER")
fun getProxies(requestObject: RequestObject) = httpOk(JsonArray().apply {
    ProxyManager.proxies.forEachIndexed { index, proxy ->
        add(interopGson.toJsonTree(proxy).asJsonObject.apply {
            addProperty("id", index)
            addProperty("type", (proxy.type ?: Proxy.Type.SOCKS5).toString())
        })
    }
})

// POST /api/v1/client/proxies/add
@Suppress("DestructuringDeclarationWithTooManyEntries")
fun postAddProxy(requestObject: RequestObject): FullHttpResponse {
    data class ProxyRequest(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val type: Proxy.Type,
        val forwardAuthentication: Boolean
    )
    val (host, port, username, password, type, forwardAuthentication) = requestObject.asJson<ProxyRequest>()

    if (host.isBlank()) {
        return httpForbidden("No host")
    }

    if (port !in 0..65535) {
        return httpForbidden("Illegal port")
    }

    ProxyManager.validateProxy(Proxy(host, port, Proxy.credentials(username, password), type, forwardAuthentication))
    return httpOk(emptyJsonObject())
}

// POST /api/v1/client/proxies/clipboard
@Suppress("UNUSED_PARAMETER")
fun postClipboardProxy(requestObject: RequestObject): FullHttpResponse {
    RenderSystem.recordRenderCall {
        runCatching {
            val clipboardText = GLFW.glfwGetClipboardString(mc.window.handle)?.trim()
            if (clipboardText.isNullOrBlank()) {
                return@runCatching
            }
            val proxyType = when {
                @Suppress("HttpUrlsUsage")
                clipboardText.startsWith("http://") -> Proxy.Type.HTTP

                clipboardText.startsWith("socks5://") || clipboardText.startsWith("socks5h://") ->
                    Proxy.Type.SOCKS5

                else -> Proxy.Type.SOCKS5 // Default to SOCKS5
            }
            val proxyText = clipboardText.substringAfter("socks5://")

            when {
                // username:password@host:port
                proxyText.contains("@") -> {
                    val credentials = proxyText.substringBefore("@")
                    val hostPort = proxyText.substringAfter("@")

                    val username = credentials.substringBefore(":")
                    val password = credentials.substringAfter(":")
                    val host = hostPort.substringBefore(":")
                    val port = hostPort.substringAfter(":").toInt()

                    ProxyManager.validateProxy(Proxy(host, port, Proxy.credentials(username, password), proxyType))
                }

                // username:password:host:port
                proxyText.count { it == ':' } == 3 -> {
                    val parts = proxyText.split(":")
                    val username = parts[0]
                    val password = parts[1]
                    val host = parts[2]
                    val port = parts[3].toInt()

                    ProxyManager.validateProxy(Proxy(host, port, Proxy.credentials(username, password), proxyType))
                }

                // host:port
                else -> {
                    val parts = proxyText.split(":")
                    val host = parts[0]
                    val port = parts[1].toInt()

                    ProxyManager.validateProxy(Proxy(host, port, null, proxyType))
                }

            }
        }.onFailure {
            logger.error("Failed to add proxy from clipboard.", it)
        }
    }

    return httpOk(emptyJsonObject())
}

// POST /api/v1/client/proxies/edit
@Suppress("DestructuringDeclarationWithTooManyEntries")
fun postEditProxy(requestObject: RequestObject): FullHttpResponse {
    data class ProxyRequest(
        val id: Int,
        val host: String,
        val port: Int,
        val type: Proxy.Type,
        val username: String,
        val password: String,
        val forwardAuthentication: Boolean
    )
    val (id, host, port, type, username, password, forwardAuthentication) = requestObject.asJson<ProxyRequest>()

    if (host.isBlank()) {
        return httpForbidden("No host")
    }

    if (port !in 0..65535) {
        return httpForbidden("Illegal port")
    }

    ProxyManager.validateProxy(Proxy(host, port, Proxy.credentials(username, password), type, forwardAuthentication), index = id)
    return httpOk(emptyJsonObject())
}

// POST /api/v1/client/proxies/check
@Suppress("UNUSED_PARAMETER")
fun postCheckProxy(requestObject: RequestObject): FullHttpResponse {
    data class ProxyRequest(val id: Int)

    val body = requestObject.asJson<ProxyRequest>()

    if (body.id < 0 || body.id >= ProxyManager.proxies.size) {
        return httpForbidden("Invalid id")
    }

    ProxyManager.validateProxy(ProxyManager.proxies[body.id], checkOnly = true)
    return httpOk(emptyJsonObject())
}

// DELETE /api/v1/client/proxies/remove
@Suppress("UNUSED_PARAMETER")
fun deleteRemoveProxy(requestObject: RequestObject): FullHttpResponse {
    data class ProxyRequest(val id: Int)

    val body = requestObject.asJson<ProxyRequest>()

    if (body.id < 0 || body.id >= ProxyManager.proxies.size) {
        return httpForbidden("Invalid id")
    }

    if (ProxyManager.proxies.removeAt(body.id) == ProxyManager.proxy) {
        ProxyManager.proxy = Proxy.NONE
    }
    return httpOk(emptyJsonObject())
}

// PUT /api/v1/client/proxies/favorite
@Suppress("UNUSED_PARAMETER")
fun putFavoriteProxy(requestObject: RequestObject): FullHttpResponse {
    data class ProxyRequest(val id: Int)

    val body = requestObject.asJson<ProxyRequest>()

    if (body.id < 0 || body.id >= ProxyManager.proxies.size) {
        return httpForbidden("Invalid id")
    }

    ProxyManager.proxies[body.id].favorite = true
    ConfigSystem.storeConfigurable(ProxyManager)
    return httpOk(emptyJsonObject())
}

// DELETE /api/v1/client/proxies/favorite
@Suppress("UNUSED_PARAMETER")
fun deleteFavoriteProxy(requestObject: RequestObject): FullHttpResponse {
    data class ProxyRequest(val id: Int)

    val body = requestObject.asJson<ProxyRequest>()

    if (body.id < 0 || body.id >= ProxyManager.proxies.size) {
        return httpForbidden("Invalid id")
    }

    ProxyManager.proxies[body.id].favorite = false
    ConfigSystem.storeConfigurable(ProxyManager)
    return httpOk(emptyJsonObject())
}

// GET /api/v1/liquidproxy/subscription
@Suppress("UNUSED_PARAMETER")
fun getProxySubscription(requestObject: RequestObject): FullHttpResponse {
    val clientAccount = ClientAccountManager.clientAccount
    if (clientAccount == ClientAccount.EMPTY_ACCOUNT) {
        return httpForbidden("No account")
    }

    if (clientAccount.proxySubscription == null) {
        runBlocking {
            clientAccount.updateProxySubscription()
        }
    }

    val subscription = clientAccount.proxySubscription
        ?: return httpForbidden("No subscription")

    return httpOk(interopGson.toJsonTree(subscription))
}

// POST /api/v1/services/liquidproxy
@Suppress("UNUSED_PARAMETER")
fun postProxyService(requestObject: RequestObject): FullHttpResponse {
    data class ProxyRequest(val level: Int, val location: String)

    val body = requestObject.asJson<ProxyRequest>()

    if (body.level < 0) {
        return httpForbidden("Invalid level")
    }
    if (body.location.isBlank()) {
        return httpForbidden("Invalid location")
    }

    val clientAccount = ClientAccountManager.clientAccount
    if (clientAccount == ClientAccount.EMPTY_ACCOUNT) {
        return httpForbidden("No account")
    }

    if (clientAccount.proxySubscription == null) {
        runBlocking {
            clientAccount.updateProxySubscription()
        }
    }

    val subscription = clientAccount.proxySubscription
        ?: return httpForbidden("No subscription")

    LiquidProxy.connect(subscription, body.level, body.location)
    return httpOk(emptyJsonObject())
}


// GET /api/v1/services/liquidproxy/locations
@Suppress("UNUSED_PARAMETER")
fun getProxyLocations(requestObject: RequestObject) = httpOk(interopGson.toJsonTree(LiquidProxy.locations))
