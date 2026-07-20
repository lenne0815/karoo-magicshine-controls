package com.lenne0815.karoomagicshine.extension

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.lenne0815.karoomagicshine.MainActivity
import com.lenne0815.karoomagicshine.R
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class LightControlsDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    private data class ButtonUi(
        val label: String,
        val background: Color,
        val allowTwoLines: Boolean = false,
        val iconRes: Int? = null,
    )

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        context.startService(
            Intent(context, MagicshineControlService::class.java)
                .setAction(MagicshineControlService.ACTION_FIELD_VISIBLE),
        )
        val scope = CoroutineScope(Dispatchers.IO)
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val viewWidth = (config.viewSize.first / density).dp
        val viewHeight = (config.viewSize.second / density).dp
        val baseTextSize = config.textSize.toFloat().coerceAtLeast(22f).sp
        var lastSignature: String? = null
        val job: Job = scope.launch {
            while (true) {
                val enabled = LightActionReceiver.isToggleEnabled(context)
                val status = LightFieldState.get(context)
                val snapshot = SharedLightState.get(context)
                val batteryPercent = RideFieldState.batteryPercent(context)
                val isFlashing = RideFieldState.isFlashing(context)
                val signature = "$RENDER_VERSION|$enabled|$status|${snapshot.outputTarget}|${snapshot.levelPercent}|${snapshot.mode}|${snapshot.lastOnTarget}|${snapshot.lastOnLevelPercent}|${snapshot.lastOnMode}|$batteryPercent|$isFlashing"
                if (lastSignature != signature) {
                    val remoteViews = glance.compose(context, DpSize(viewWidth, viewHeight)) {
                        LightRideField(
                            toggleUi = buildToggleUi(enabled, status, snapshot),
                            flashUi = ButtonUi(
                                label = "FLASH",
                                background = if (isFlashing) ORANGE_COLOR else CARD_COLOR,
                                iconRes = R.drawable.ic_flash_on,
                            ),
                            batteryLabel = batteryPercent?.let { "$it%" } ?: "--%",
                            totalWidth = viewWidth,
                            totalHeight = viewHeight,
                            baseTextSize = baseTextSize,
                        )
                    }
                    emitter.updateView(remoteViews.remoteViews)
                    lastSignature = signature
                }
                delay(1000)
            }
        }
        emitter.setCancellable {
            job.cancel()
            context.startService(
                Intent(context, MagicshineControlService::class.java)
                    .setAction(MagicshineControlService.ACTION_FIELD_HIDDEN),
            )
        }
    }

    private fun buildToggleUi(enabled: Boolean, status: String, snapshot: SharedLightState.Snapshot): ButtonUi {
        val actualStateLabel = buildActualStateLabel(snapshot)
        val actualStateIsOff = snapshot.outputTarget == SharedLightState.OutputTarget.OFF || !enabled
        val ui = when (status) {
            LightFieldState.STATUS_SEARCHING ->
                ButtonUi("SEARCH", CARD_COLOR, iconRes = R.drawable.ic_sync_alt)
            LightFieldState.STATUS_FOUND ->
                ButtonUi(actualStateLabel, CARD_COLOR, iconRes = R.drawable.ic_flashlight_on)
            LightFieldState.STATUS_CONNECTING ->
                ButtonUi("CONNECT", CARD_COLOR, allowTwoLines = true, iconRes = R.drawable.ic_sync_alt)
            LightFieldState.STATUS_CONNECTED -> if (actualStateIsOff) {
                ButtonUi("OFF", CARD_COLOR, iconRes = R.drawable.ic_power_settings_new)
            } else {
                ButtonUi(actualStateLabel, GREEN_COLOR, iconRes = R.drawable.ic_flashlight_on)
            }
            LightFieldState.STATUS_NO_DEVICE ->
                ButtonUi("NO\nLAMP", ORANGE_COLOR, allowTwoLines = true, iconRes = R.drawable.ic_link_off)
            LightFieldState.STATUS_ERROR ->
                ButtonUi("ERROR", ORANGE_COLOR, iconRes = R.drawable.ic_e911_emergency)
            LightFieldState.STATUS_DISCONNECTED,
            LightFieldState.STATUS_IDLE ->
                ButtonUi(actualStateLabel, CARD_COLOR, iconRes = R.drawable.ic_link_off)
            else ->
                ButtonUi(actualStateLabel, CARD_COLOR, iconRes = R.drawable.ic_flashlight_on)
        }
        return ui
    }

    private fun buildActualStateLabel(snapshot: SharedLightState.Snapshot): String {
        if (snapshot.outputTarget == SharedLightState.OutputTarget.OFF) return "OFF"
        val level = snapshot.levelPercent ?: snapshot.lastOnLevelPercent ?: 100
        val prefix = when (snapshot.outputTarget) {
            SharedLightState.OutputTarget.HIGH -> "H"
            SharedLightState.OutputTarget.LOW,
            SharedLightState.OutputTarget.OFF -> "L"
        }
        return "$prefix$level"
    }

    @Composable
    private fun LightRideField(
        toggleUi: ButtonUi,
        flashUi: ButtonUi,
        batteryLabel: String,
        totalWidth: Dp,
        totalHeight: Dp,
        baseTextSize: TextUnit,
    ) {
        val outerPadding = 2.dp
        val gap = 2.dp
        val cellWidth = (
            (totalWidth.value - (outerPadding.value * 2f) - (gap.value * 3f)) / 4f
        ).coerceAtLeast(28f).dp
        val toggleTextSize = fieldTextSize(
            toggleUi.label,
            cellWidth,
            totalHeight,
            baseTextSize,
            toggleUi.allowTwoLines,
            hasIcon = true,
        )
        val flashTextSize = fieldTextSize(flashUi.label, cellWidth, totalHeight, baseTextSize, false, hasIcon = true)
        val batteryTextSize = fieldTextSize(batteryLabel, cellWidth, totalHeight, baseTextSize, false, hasIcon = true)
        val appTextSize = fieldTextSize("APP", cellWidth, totalHeight, baseTextSize, false, hasIcon = true)
        val iconSize = (totalHeight.value * 0.24f).coerceIn(14f, 20f).dp
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = outerPadding, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FieldCell(
                label = toggleUi.label,
                background = toggleUi.background,
                modifier = GlanceModifier
                    .width(cellWidth)
                    .fillMaxHeight()
                    .clickable(actionRunCallback<ToggleLightAction>()),
                textSize = toggleTextSize,
                maxLines = if (toggleUi.allowTwoLines) 2 else 1,
                iconRes = toggleUi.iconRes,
                iconSize = iconSize,
            )
            Spacer(modifier = GlanceModifier.width(gap))
            FieldCell(
                label = flashUi.label,
                background = flashUi.background,
                modifier = GlanceModifier
                    .width(cellWidth)
                    .fillMaxHeight()
                    .clickable(actionRunCallback<FlashLightAction>()),
                textSize = flashTextSize,
                maxLines = 1,
                iconRes = flashUi.iconRes,
                iconSize = iconSize,
            )
            Spacer(modifier = GlanceModifier.width(gap))
            FieldCell(
                label = batteryLabel,
                background = CARD_DARK_COLOR,
                modifier = GlanceModifier
                    .width(cellWidth)
                    .fillMaxHeight(),
                textSize = batteryTextSize,
                maxLines = 1,
                iconRes = R.drawable.ic_battery_level,
                iconSize = iconSize,
            )
            Spacer(modifier = GlanceModifier.width(gap))
            FieldCell(
                label = "APP",
                background = CARD_DARK_COLOR,
                modifier = GlanceModifier
                    .width(cellWidth)
                    .fillMaxHeight()
                    .clickable(actionStartActivity<MainActivity>()),
                textSize = appTextSize,
                maxLines = 1,
                iconRes = R.drawable.ic_apps,
                iconSize = iconSize,
            )
        }
    }

    private fun fieldTextSize(
        label: String,
        cellWidth: Dp,
        totalHeight: Dp,
        baseTextSize: TextUnit,
        allowTwoLines: Boolean,
        hasIcon: Boolean = false,
    ): TextUnit {
        val longestLine = label.split('\n').maxOf { it.length.coerceAtLeast(1) }
        val heightDriven = if (allowTwoLines || hasIcon) {
            (totalHeight.value * 0.22f).coerceIn(16f, 24f)
        } else {
            (totalHeight.value * 0.30f).coerceIn(22f, 34f)
        }
        val widthDriven = when {
            longestLine <= 3 -> 30f
            longestLine <= 4 -> 26f
            longestLine <= 5 -> 22f
            longestLine <= 6 -> 18f
            else -> 16f
        }.coerceAtMost((cellWidth.value * 0.28f).coerceAtLeast(12f))
        return minOf(baseTextSize.value.coerceAtLeast(heightDriven), widthDriven).sp
    }

    @Composable
    private fun FieldCell(
        label: String,
        background: Color,
        modifier: GlanceModifier,
        textSize: TextUnit,
        maxLines: Int,
        iconRes: Int? = null,
        iconSize: Dp = 0.dp,
    ) {
        Box(
            modifier = modifier
                .background(ColorProvider(background, background))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (iconRes != null) {
                    Image(
                        provider = ImageProvider(iconRes),
                        contentDescription = label,
                        modifier = GlanceModifier.size(iconSize),
                    )
                }
                Text(
                    text = label,
                    maxLines = maxLines,
                    style = TextStyle(
                        color = ColorProvider(Color.White, Color.White),
                        fontSize = textSize,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }

    companion object {
        const val TYPE_ID = "DATATYPE_LIGHT_CONTROLS"
        private const val RENDER_VERSION = 12

        private val GREEN_COLOR = Color(0xFF20D39B)
        private val CARD_COLOR = Color(0xFF6B6B6B)
        private val CARD_DARK_COLOR = Color(0xFF575757)
        private val ORANGE_COLOR = Color(0xFFFF6B00)
    }
}
