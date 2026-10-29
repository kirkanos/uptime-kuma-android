package net.kirkanos.uptimekuma.widget.state

/** What the Quick Settings tile shows; turned into localized text by the tile service. */
sealed interface TileSubtitle {
    data object NotConfigured : TileSubtitle

    data object NoMonitors : TileSubtitle

    data object AllUp : TileSubtitle

    data class Down(
        val count: Int,
    ) : TileSubtitle

    data class Pending(
        val count: Int,
    ) : TileSubtitle
}

/** Tile content: [active] (highlighted tile) only when everything is up. */
data class TileSummary(
    val subtitle: TileSubtitle,
    val active: Boolean,
) {
    companion object {
        fun of(state: MonitorsWidgetState): TileSummary =
            when (state.overall) {
                OverallStatus.NOT_CONFIGURED -> TileSummary(TileSubtitle.NotConfigured, active = false)
                OverallStatus.NO_MONITORS -> TileSummary(TileSubtitle.NoMonitors, active = false)
                OverallStatus.ALL_UP -> TileSummary(TileSubtitle.AllUp, active = true)
                OverallStatus.PENDING -> TileSummary(TileSubtitle.Pending(state.pending), active = false)
                OverallStatus.DOWN -> TileSummary(TileSubtitle.Down(state.down), active = false)
            }
    }
}
