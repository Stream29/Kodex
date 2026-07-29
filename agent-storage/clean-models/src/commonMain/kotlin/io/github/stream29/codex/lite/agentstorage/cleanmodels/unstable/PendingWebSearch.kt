package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Source protocol for a pending web-search interaction. */
@Serializable
public enum class PendingWebSearchSource {
    @SerialName("web_run")
    WebRun,

    @SerialName("hosted")
    Hosted,
}

/** One command within a pending web-search interaction. */
@Serializable
public sealed interface PendingWebSearchOperation {
    /** Text search query. */
    @Serializable
    @SerialName("search_query")
    public data class SearchQuery(
        public val query: String,
        @SerialName("recency_days")
        public val recencyDays: Long? = null,
        public val domains: List<String>? = null,
    ) : PendingWebSearchOperation

    /** Image search query. */
    @Serializable
    @SerialName("image_query")
    public data class ImageQuery(
        public val query: String,
        @SerialName("recency_days")
        public val recencyDays: Long? = null,
        public val domains: List<String>? = null,
    ) : PendingWebSearchOperation

    /** Open a result reference or URL. */
    @Serializable
    @SerialName("open")
    public data class Open(
        public val reference: String,
        public val line: Long? = null,
    ) : PendingWebSearchOperation

    /** Follow a numbered link from an opened reference. */
    @Serializable
    @SerialName("click")
    public data class Click(
        public val reference: String,
        @SerialName("link_id")
        public val linkId: Long,
    ) : PendingWebSearchOperation

    /** Find text inside an opened reference. */
    @Serializable
    @SerialName("find")
    public data class Find(
        public val reference: String,
        public val pattern: String,
    ) : PendingWebSearchOperation

    /** Capture one PDF page. */
    @Serializable
    @SerialName("screenshot")
    public data class Screenshot(
        public val reference: String,
        @SerialName("page_number")
        public val pageNumber: Long,
    ) : PendingWebSearchOperation

    /** Query one finance instrument. */
    @Serializable
    @SerialName("finance")
    public data class Finance(
        public val ticker: String,
        @SerialName("asset_type")
        public val assetType: PendingFinanceAssetType,
        public val market: String? = null,
    ) : PendingWebSearchOperation

    /** Query a weather forecast. */
    @Serializable
    @SerialName("weather")
    public data class Weather(
        public val location: String,
        public val start: String? = null,
        @SerialName("duration_days")
        public val durationDays: Long? = null,
    ) : PendingWebSearchOperation

    /** Query a sports schedule or standings. */
    @Serializable
    @SerialName("sports")
    public data class Sports(
        public val function: PendingSportsFunction,
        public val league: PendingSportsLeague,
        public val team: String? = null,
        public val opponent: String? = null,
        @SerialName("date_from")
        public val dateFrom: String? = null,
        @SerialName("date_to")
        public val dateTo: String? = null,
        @SerialName("num_games")
        public val numGames: Long? = null,
        public val locale: String? = null,
    ) : PendingWebSearchOperation

    /** Query the current time at one UTC offset. */
    @Serializable
    @SerialName("time")
    public data class Time(
        @SerialName("utc_offset")
        public val utcOffset: String,
    ) : PendingWebSearchOperation

    /** Hosted provider action without a recognized typed representation. */
    @Serializable
    @SerialName("other")
    public data object Other : PendingWebSearchOperation
}

/** Asset type accepted by a pending finance lookup. */
@Serializable
public enum class PendingFinanceAssetType {
    @SerialName("equity")
    Equity,

    @SerialName("fund")
    Fund,

    @SerialName("crypto")
    Crypto,

    @SerialName("index")
    Index,
}

/** Sports operation performed by a pending lookup. */
@Serializable
public enum class PendingSportsFunction {
    @SerialName("schedule")
    Schedule,

    @SerialName("standings")
    Standings,
}

/** League accepted by the current web-search sports backend. */
@Serializable
public enum class PendingSportsLeague {
    @SerialName("nba")
    Nba,

    @SerialName("wnba")
    Wnba,

    @SerialName("nfl")
    Nfl,

    @SerialName("nhl")
    Nhl,

    @SerialName("mlb")
    Mlb,

    @SerialName("epl")
    Epl,

    @SerialName("ncaamb")
    Ncaamb,

    @SerialName("ncaawb")
    Ncaawb,

    @SerialName("ipl")
    Ipl,
}

/** Requested response size for a pending local `web.run` batch. */
@Serializable
public enum class PendingWebSearchResponseLength {
    @SerialName("short")
    Short,

    @SerialName("medium")
    Medium,

    @SerialName("long")
    Long,
}
