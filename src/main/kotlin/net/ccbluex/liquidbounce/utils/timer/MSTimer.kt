// Dán toàn bộ code này vào tệp MSTimer.kt
package net.ccbluex.liquidbounce.utils.timer

class MSTimer {

    var time = System.currentTimeMillis()

    fun hasTimePassed(ms: Long): Boolean {
        return System.currentTimeMillis() >= time + ms
    }

    fun hasTimeLeft(ms: Long): Long {
        return ms + time - System.currentTimeMillis()
    }

    fun reset() {
        time = System.currentTimeMillis()
    }
}