package com.lenne0815.karoomagicshine.extension

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Called on the service main thread. Adjacent pages may hide the old view before showing the new one. */
internal class RideFieldVisibility(private val mainScope: CoroutineScope) {
    private var count = 0
    private var pendingHide: Job? = null
    val visible: Boolean get() = count > 0

    fun shown(): Boolean {
        val first = !visible && pendingHide == null
        count++
        pendingHide?.cancel()
        pendingHide = null
        return first
    }

    fun hidden(appIsActive: () -> Boolean, onLastHidden: () -> Unit) {
        if (!visible) return
        count--
        if (visible) return
        pendingHide = mainScope.launch {
            delay(750)
            pendingHide = null
            if (!appIsActive()) onLastHidden()
        }
    }
}
