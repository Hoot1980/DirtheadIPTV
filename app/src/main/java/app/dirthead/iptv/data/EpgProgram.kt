package app.dirthead.iptv.data

data class EpgProgram(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    /** Programme synopsis when provided by XMLTV or provider API. */
    val description: String? = null,
)
