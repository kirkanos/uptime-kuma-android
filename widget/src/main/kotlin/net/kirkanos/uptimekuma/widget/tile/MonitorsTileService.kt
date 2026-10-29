package net.kirkanos.uptimekuma.widget.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.widget.R
import net.kirkanos.uptimekuma.widget.state.MonitorsWidgetState
import net.kirkanos.uptimekuma.widget.state.TileSubtitle
import net.kirkanos.uptimekuma.widget.state.TileSummary
import net.kirkanos.uptimekuma.widget.state.WidgetStateHolder
import javax.inject.Inject

/**
 * Quick Settings tile "Uptime Kuma" with the overall status as subtitle ("All up", "2 down", ...). Declared
 * as active tile: the system binds it only when `WidgetUpdater` requests it after a state change (or when the
 * user adds / taps it), so it costs nothing while monitors are stable. Tap opens the app.
 */
@AndroidEntryPoint
class MonitorsTileService : TileService() {
    @Inject lateinit var holder: WidgetStateHolder

    private val scope = MainScope()
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening =
            scope.launch {
                render(holder.current())
                holder.state.filterNotNull().collect(::render)
            }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::openApp) else openApp()
    }

    private fun render(state: MonitorsWidgetState) {
        val tile = qsTile ?: return
        val summary = TileSummary.of(state)
        tile.label = getString(R.string.tile_label)
        tile.subtitle = subtitleText(this, summary.subtitle)
        tile.state = if (summary.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_kuma)
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent =
            packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    internal companion object {
        fun subtitleText(
            context: Context,
            subtitle: TileSubtitle,
        ): String {
            val res = context.resources
            return when (subtitle) {
                TileSubtitle.NotConfigured -> {
                    res.getString(R.string.widget_not_configured)
                }

                TileSubtitle.NoMonitors -> {
                    res.getString(R.string.widget_no_monitors)
                }

                TileSubtitle.AllUp -> {
                    res.getString(R.string.widget_all_up)
                }

                is TileSubtitle.Down -> {
                    res.getQuantityString(R.plurals.widget_down, subtitle.count, subtitle.count)
                }

                is TileSubtitle.Pending -> {
                    res.getQuantityString(R.plurals.widget_pending, subtitle.count, subtitle.count)
                }
            }
        }
    }
}
