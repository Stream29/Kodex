package io.github.stream29.kodex.openai

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * @property reasoning Reasoning controls, including an explicit default effort.
 * @property commands Nullable because callers may use settings-only requests;
 * `null` means no command group is sent.
 * @property settings Nullable because hosted search settings are optional;
 * `null` means use backend defaults.
 * @property maxOutputTokens Nullable because output budget is optional; `null`
 * means use the backend default.
 */
@Serializable
public data class SearchRequest(
    public val id: String,
    public val model: OpenAiModelId,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    public val reasoning: Reasoning = Reasoning(),
    public val commands: SearchCommands? = null,
    public val settings: SearchSettings? = null,
    @SerialName("max_output_tokens")
    public val maxOutputTokens: Long? = null,
)

/**
 * @property searchQuery Nullable because the search command group is optional;
 * `null` means no text searches are requested.
 * @property imageQuery Nullable because the image command group is optional;
 * `null` means no image searches are requested.
 * @property open Nullable because open operations are optional; `null` means no
 * pages are opened.
 * @property click Nullable because click operations are optional; `null` means
 * no result links are clicked.
 * @property find Nullable because find operations are optional; `null` means no
 * in-page search is requested.
 * @property screenshot Nullable because screenshots are optional; `null` means
 * no screenshot capture is requested.
 * @property finance Nullable because finance lookups are optional; `null` means
 * no finance data is requested.
 * @property weather Nullable because weather lookups are optional; `null` means
 * no weather data is requested.
 * @property sports Nullable because sports lookups are optional; `null` means
 * no sports data is requested.
 * @property time Nullable because time lookups are optional; `null` means no
 * time data is requested.
 * @property responseLength Nullable because response length is optional; `null`
 * means use the backend default.
 */
@Serializable
public data class SearchCommands(
    @SerialName("search_query")
    public val searchQuery: List<SearchQuery>? = null,
    @SerialName("image_query")
    public val imageQuery: List<SearchQuery>? = null,
    public val open: List<OpenOperation>? = null,
    public val click: List<ClickOperation>? = null,
    public val find: List<FindOperation>? = null,
    public val screenshot: List<ScreenshotOperation>? = null,
    public val finance: List<FinanceOperation>? = null,
    public val weather: List<WeatherOperation>? = null,
    public val sports: List<SportsOperation>? = null,
    public val time: List<TimeOperation>? = null,
    @SerialName("response_length")
    public val responseLength: SearchResponseLength? = null,
)

/**
 * @property recency Nullable because recency filtering is optional; `null`
 * means no recency filter is applied.
 * @property domains Nullable because domain filtering is optional; `null` means
 * no domain filter is applied.
 */
@Serializable
public data class SearchQuery(
    public val q: String,
    public val recency: Long? = null,
    public val domains: List<String>? = null,
)

/**
 * @property lineno Nullable because opening a ref can omit a target line; `null`
 * means the backend chooses the default or most relevant position.
 */
@Serializable
public data class OpenOperation(
    @SerialName("ref_id")
    public val refId: String,
    public val lineno: Long? = null,
)

@Serializable
public data class ClickOperation(
    @SerialName("ref_id")
    public val refId: String,
    public val id: Long,
)

@Serializable
public data class FindOperation(
    @SerialName("ref_id")
    public val refId: String,
    public val pattern: String,
)

@Serializable
public data class ScreenshotOperation(
    @SerialName("ref_id")
    public val refId: String,
    public val pageno: Long,
)

/**
 * @property market Nullable because some asset types do not need an exchange market;
 * `null` means no market qualifier is sent.
 */
@Serializable
public data class FinanceOperation(
    public val ticker: String,
    public val type: FinanceAssetType,
    public val market: String? = null,
)

@Serializable
public enum class FinanceAssetType {
    @SerialName("equity")
    Equity,

    @SerialName("fund")
    Fund,

    @SerialName("crypto")
    Crypto,

    @SerialName("index")
    Index,
}

/**
 * @property start Nullable because weather lookup start date is optional;
 * `null` means omit the start bound.
 * @property duration Nullable because weather lookup duration is optional;
 * `null` means omit the duration bound.
 */
@Serializable
public data class WeatherOperation(
    public val location: String,
    public val start: String? = null,
    public val duration: Long? = null,
)

/**
 * @property tool Nullable because the legacy tool discriminator is optional;
 * `null` means omit it.
 * @property team Nullable because team filtering is optional; `null` means no
 * team filter is applied.
 * @property opponent Nullable because opponent filtering is optional; `null`
 * means no opponent filter is applied.
 * @property dateFrom Nullable because date range start is optional; `null`
 * means no lower date bound is sent.
 * @property dateTo Nullable because date range end is optional; `null` means no
 * upper date bound is sent.
 * @property numGames Nullable because result count is optional; `null` means
 * use the backend default.
 * @property locale Nullable because locale is optional; `null` means use the
 * backend default locale.
 */
@Serializable
public data class SportsOperation(
    public val tool: SportsToolName? = null,
    @SerialName("fn")
    public val function: SportsFunction,
    public val league: SportsLeague,
    public val team: String? = null,
    public val opponent: String? = null,
    @SerialName("date_from")
    public val dateFrom: String? = null,
    @SerialName("date_to")
    public val dateTo: String? = null,
    @SerialName("num_games")
    public val numGames: Long? = null,
    public val locale: String? = null,
)

@Serializable
public enum class SportsToolName {
    @SerialName("sports")
    Sports,
}

@Serializable
public enum class SportsFunction {
    @SerialName("schedule")
    Schedule,

    @SerialName("standings")
    Standings,
}

@Serializable
public enum class SportsLeague {
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

@Serializable
public data class TimeOperation(
    @SerialName("utc_offset")
    public val utcOffset: String,
)

@Serializable
public enum class SearchResponseLength {
    @SerialName("short")
    Short,

    @SerialName("medium")
    Medium,

    @SerialName("long")
    Long,
}

/**
 * @property externalWebAccess Nullable because live-web control is optional;
 * `null` means use the backend default.
 */
@Serializable
public data class SearchSettings(
    @SerialName("external_web_access")
    public val externalWebAccess: Boolean? = null,
)

/**
 * @property encryptedOutput Nullable because not every response carries encrypted
 * side-channel state; `null` means there is no encrypted payload to preserve.
 */
@Serializable
public data class SearchResponse(
    @SerialName("encrypted_output")
    public val encryptedOutput: String? = null,
    public val output: String,
)
