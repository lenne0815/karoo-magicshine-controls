package com.lenne0815.karoomagicshine.extension

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

class ToggleLightAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val action = if (LightFieldState.get(context) == LightFieldState.STATUS_CONNECTED) {
            MagicshineControlService.ACTION_TOGGLE_100
        } else {
            MagicshineControlService.ACTION_RETRY_CONNECT
        }
        context.startService(
            Intent(context, MagicshineControlService::class.java)
                .setAction(action),
        )
    }
}
