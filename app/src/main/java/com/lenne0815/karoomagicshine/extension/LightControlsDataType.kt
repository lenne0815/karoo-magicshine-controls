package com.lenne0815.karoomagicshine.extension

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.lenne0815.karoomagicshine.R
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig

class LightControlsDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = true))

        val rv = RemoteViews(context.packageName, R.layout.view_light_controls)
        rv.setOnClickPendingIntent(R.id.btnFieldOff, pending(context, LightActionReceiver.ACTION_OFF, 1))
        rv.setOnClickPendingIntent(R.id.btnField10, pending(context, LightActionReceiver.ACTION_10, 2))
        rv.setOnClickPendingIntent(R.id.btnField100, pending(context, LightActionReceiver.ACTION_100, 3))

        emitter.updateView(rv)
        emitter.setCancellable { }
    }

    private fun pending(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, LightActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val TYPE_ID = "DATATYPE_LIGHT_CONTROLS"
    }
}
