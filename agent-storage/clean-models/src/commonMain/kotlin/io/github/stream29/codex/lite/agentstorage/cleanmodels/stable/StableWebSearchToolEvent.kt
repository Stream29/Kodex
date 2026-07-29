package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of a completed web-search interaction.
 *
 * The event normalizes local `web.run` batches and hosted web-search actions
 * into an ordered list of strongly typed [operations].
 */
@Serializable
@SerialName("web_search_tool_event")
public data class StableWebSearchToolEvent(
    public val source: StableWebSearchSource,
    public val operations: List<StableWebSearchOperation>,
    @SerialName("response_length")
    public val responseLength: StableWebSearchResponseLength? = null,
    public val result: StableWebSearchResult,
) : StableToolEvent

/** Source protocol that produced a web-search event. */
@Serializable
public enum class StableWebSearchSource {
    @SerialName("web_run")
    WebRun,

    @SerialName("hosted")
    Hosted,
}

/** One command within a web-search interaction. */
@Serializable
public sealed interface StableWebSearchOperation {
    /** Text search query. */
    @Serializable
    @SerialName("search_query")
    public data class SearchQuery(
        public val query: String,
        @SerialName("recency_days")
        public val recencyDays: Long? = null,
        public val domains: List<String>? = null,
    ) : StableWebSearchOperation

    /** Image search query. */
    @Serializable
    @SerialName("image_query")
    public data class ImageQuery(
        public val query: String,
        @SerialName("recency_days")
        public val recencyDays: Long? = null,
        public val domains: List<String>? = null,
    ) : StableWebSearchOperation

    /** Open a result reference or URL. */
    @Serializable
    @SerialName("open")
    public data class Open(
        public val reference: String,
        public val line: Long? = null,
    ) : StableWebSearchOperation

    /** Follow a numbered link from an opened reference. */
    @Serializable
    @SerialName("click")
    public data class Click(
        public val reference: String,
        @SerialName("link_id")
        public val linkId: Long,
    ) : StableWebSearchOperation

    /** Find text inside an opened reference. */
    @Serializable
    @SerialName("find")
    public data class Find(
        public val reference: String,
        public val pattern: String,
    ) : StableWebSearchOperation

    /** Capture one PDF page. */
    @Serializable
    @SerialName("screenshot")
    public data class Screenshot(
        public val reference: String,
        @SerialName("page_number")
        public val pageNumber: Long,
    ) : StableWebSearchOperation

    /** Query one finance instrument. */
    @Serializable
    @SerialName("finance")
    public data class Finance(
        public val ticker: String,
        @SerialName("asset_type")
        public val assetType: StableFinanceAssetType,
        public val market: String? = null,
    ) : StableWebSearchOperation

    /** Query a weather forecast. */
    @Serializable
    @SerialName("weather")
    public data class Weather(
        public val location: String,
        public val start: String? = null,
        @SerialName("duration_days")
        public val durationDays: Long? = null,
    ) : StableWebSearchOperation

    /** Query a sports schedule or standings. */
    @Serializable
    @SerialName("sports")
    public data class Sports(
        public val function: StableSportsFunction,
        public val league: StableSportsLeague,
        public val team: String? = null,
        public val opponent: String? = null,
        @SerialName("date_from")
        public val dateFrom: String? = null,
        @SerialName("date_to")
        public val dateTo: String? = null,
        @SerialName("num_games")
        public val numGames: Long? = null,
        public val locale: String? = null,
    ) : StableWebSearchOperation

    /** Query the current time at one UTC offset. */
    @Serializable
    @SerialName("time")
    public data class Time(
        @SerialName("utc_offset")
        public val utcOffset: String,
    ) : StableWebSearchOperation

    /** Hosted provider action that has no recognized stable representation. */
    @Serializable
    @SerialName("other")
    public data object Other : StableWebSearchOperation
}

/** Asset type accepted by a finance lookup. */
@Serializable
public enum class StableFinanceAssetType {
    @SerialName("equity")
    Equity,

    @SerialName("fund")
    Fund,

    @SerialName("crypto")
    Crypto,

    @SerialName("index")
    Index,
}

/** Sports operation performed by a lookup. */
@Serializable
public enum class StableSportsFunction {
    @SerialName("schedule")
    Schedule,

    @SerialName("standings")
    Standings,
}

/** League accepted by the current web-search sports backend. */
@Serializable
public enum class StableSportsLeague {
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

/** Requested response size for a local `web.run` batch. */
@Serializable
public enum class StableWebSearchResponseLength {
    @SerialName("short")
    Short,

    @SerialName("medium")
    Medium,

    @SerialName("long")
    Long,
}

/** Completed outcome of a web-search interaction. */
@Serializable
public sealed interface StableWebSearchResult {
    /**
     * Web search completed.
     *
     * [output] is nullable because hosted search exposes the action as a
     * history item without a separate text result.
     */
    @Serializable
    @SerialName("success")
    public data class Success(
        public val output: String? = null,
    ) : StableWebSearchResult

    /** Web search failed. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableWebSearchResult
}
