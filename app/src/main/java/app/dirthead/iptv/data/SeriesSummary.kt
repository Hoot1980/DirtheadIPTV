package app.dirthead.iptv.data

data class SeriesSummary(
    val seriesId: String,
    val name: String,
    val groupTitle: String,
    val coverUrl: String? = null,
)
