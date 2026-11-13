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
internal class LightControlsDataType(extension: String, private val layout: RideFieldLayout) :
    DataTypeImpl(extension, layout.typeId) {

    private data class ButtonUi(
        val label: String,
        val background: Color,
        val allowTwoLines: Boolean = false,
        val iconRes: Int? = null,
    )

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val usesLamp = layout.usesLamp(config.preview)
        fun notifyService(action: String) {
            if (usesLamp) context.startService(Intent(context, MagicshineControlService::class.java).setAction(action))
        }
        notifyService(MagicshineControlService.ACTION_FIELD_VISIBLE)
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val width = (config.viewSize.first / density).dp
        val height = (config.viewSize.second / density).dp
        val textSize = config.textSize.toFloat().coerceAtLeast(22f).sp
        val job: Job = CoroutineScope(Dispatchers.IO).launch {
            var previous: List<ButtonUi>? = null
            do {
                val cells = layout.tiles.map { tile ->
                    when (tile) {
                        LightTile.TOGGLE -> if (config.preview) {
                            ButtonUi("OFF", CARD_COLOR, iconRes = R.drawable.ic_power_settings_new)
                        } else buildToggleUi(LightFieldState.get(context), SharedLightState.get(context))
                        LightTile.FLASH -> ButtonUi(
                            "FLASH",
                            if (!config.preview && RideFieldState.isFlashing(context)) ORANGE_COLOR else CARD_COLOR,
                            iconRes = R.drawable.ic_flash_on,
                        )
                        LightTile.BATTERY -> buildBatteryUi(if (config.preview) "HIGH" else RideFieldState.batteryStatus(context))
                        LightTile.APP -> ButtonUi("APP", CARD_DARK_COLOR, iconRes = R.drawable.ic_apps)
                    }
                }
                if (previous != cells) {
                    val view = glance.compose(context, DpSize(width, height)) {
                        LightRideField(cells, width, height, textSize, interactive = !config.preview)
                    }
                    emitter.updateView(view.remoteViews)
                    previous = cells
                }
                if (!usesLamp) break
                delay(1000)
            } while (true)
        }
        emitter.setCancellable {
            job.cancel()
            notifyService(MagicshineControlService.ACTION_FIELD_HIDDEN)
        }
    }

    private fun buildToggleUi(status: String, snapshot: SharedLightState.Snapshot): ButtonUi {
        val actualStateLabel = buildActualStateLabel(snapshot)
        val actualStateIsOff = !snapshot.isOn
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

    private fun buildBatteryUi(status: String?): ButtonUi = when (status) {
        "HIGH" -> ButtonUi("HIGH", GREEN_COLOR, iconRes = R.drawable.ic_battery_level)
        "MID" -> ButtonUi("MID", ORANGE_COLOR, iconRes = R.drawable.ic_battery_level)
        "LOW" -> ButtonUi("LOW", LOW_COLOR, iconRes = R.drawable.ic_battery_level)
        null -> ButtonUi("--", CARD_DARK_COLOR, iconRes = R.drawable.ic_battery_level)
        else -> ButtonUi(status, CARD_DARK_COLOR, iconRes = R.drawable.ic_battery_level)
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
        cells: List<ButtonUi>,
        totalWidth: Dp,
        totalHeight: Dp,
        baseTextSize: TextUnit,
        interactive: Boolean,
    ) {
        val outerPadding = 2.dp
        val gap = 2.dp
        val availableWidth = totalWidth.value - outerPadding.value * 2
        val cellWidth = if (cells.size == 1) availableWidth.coerceAtLeast(0f).dp
            else ((availableWidth - gap.value * (cells.size - 1)) / cells.size).coerceAtLeast(28f).dp
        val iconSize = (totalHeight.value * 0.24f).coerceIn(14f, 20f).dp
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = outerPadding, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            cells.forEachIndexed { index, cell ->
                if (index > 0) Spacer(modifier = GlanceModifier.width(gap))
                val bounds = GlanceModifier.width(cellWidth).fillMaxHeight()
                val modifier = if (!interactive) bounds else when (layout.tiles[index]) {
                    LightTile.TOGGLE -> bounds.clickable(actionRunCallback<ToggleLightAction>())
                    LightTile.FLASH -> bounds.clickable(actionRunCallback<FlashLightAction>())
                    LightTile.BATTERY -> bounds
                    LightTile.APP -> bounds.clickable(actionStartActivity<MainActivity>())
                }
                FieldCell(
                    label = cell.label,
                    background = cell.background,
                    modifier = modifier,
                    textSize = fieldTextSize(cell.label, cellWidth, totalHeight, baseTextSize, cell.allowTwoLines, true),
                    maxLines = if (cell.allowTwoLines) 2 else 1,
                    iconRes = cell.iconRes,
                    iconSize = iconSize,
                )
            }
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
        private val GREEN_COLOR = Color(0xFF20D39B)
        private val CARD_COLOR = Color(0xFF6B6B6B)
        private val CARD_DARK_COLOR = Color(0xFF575757)
        private val ORANGE_COLOR = Color(0xFFFF6B00)
        private val LOW_COLOR = Color(0xFFD93D3D)
    }
}
