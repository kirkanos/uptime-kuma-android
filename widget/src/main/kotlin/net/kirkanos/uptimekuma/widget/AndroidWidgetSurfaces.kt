package net.kirkanos.uptimekuma.widget

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.util.Log
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import net.kirkanos.uptimekuma.widget.state.WidgetSurfaces
import net.kirkanos.uptimekuma.widget.tile.MonitorsTileService
import javax.inject.Inject

/** Glance widget + Quick Settings tile. Failures are logged, never thrown into the updater. */
class AndroidWidgetSurfaces
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : WidgetSurfaces {
        override suspend fun refreshWidgets() {
            try {
                MonitorsWidget().updateAll(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Widget update failed", e)
            }
        }

        override fun requestTileUpdate() {
            try {
                TileService.requestListeningState(context, ComponentName(context, MonitorsTileService::class.java))
            } catch (e: RuntimeException) {
                // Thrown e.g. when the tile service is not exported/known; the tile then updates when opened.
                Log.w(TAG, "Tile listening request failed", e)
            }
        }

        private companion object {
            const val TAG = "WidgetSurfaces"
        }
    }
