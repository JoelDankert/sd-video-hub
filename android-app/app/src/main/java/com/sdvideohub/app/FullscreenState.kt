package com.sdvideohub.app

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal class FullscreenSessionState {
    var isActive: Boolean = false
        private set

    fun begin(): Boolean {
        if (isActive) return false
        isActive = true
        return true
    }

    fun end(): Boolean {
        if (!isActive) return false
        isActive = false
        return true
    }
}

internal data class SystemUiState(
    val statusBarsVisible: Boolean,
    val navigationBarsVisible: Boolean,
    val behavior: Int,
) {
    fun restore(controller: WindowInsetsControllerCompat) {
        controller.systemBarsBehavior = behavior
        restoreType(controller, WindowInsetsCompat.Type.statusBars(), statusBarsVisible)
        restoreType(controller, WindowInsetsCompat.Type.navigationBars(), navigationBarsVisible)
    }

    private fun restoreType(controller: WindowInsetsControllerCompat, type: Int, visible: Boolean) {
        if (visible) controller.show(type) else controller.hide(type)
    }
}
