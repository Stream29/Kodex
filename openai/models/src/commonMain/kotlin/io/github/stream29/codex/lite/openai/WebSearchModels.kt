package io.github.stream29.codex.lite.openai
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * @property reasoning Reasoning controls. The default value is omitted from
 * the wire.
 * @property input Nullable because callers may send commands without explicit
 * conversation input; `null` means no input payload is sent.
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
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val reasoning: Reasoning = Reasoning(),
    public val input: SearchInput? = null,
    public val commands: SearchCommands? = null,
    public val settings: SearchSettings? = null,
    @SerialName("max_output_tokens")
    public val maxOutputTokens: Long? = null,
)

@Serializable(with = SearchInputSerializer::class)
public sealed interface SearchInput {
    public data class Text(public val text: String) : SearchInput
    public data class Items(public val items: List<ResponseItem>) : SearchInput
}

public object SearchInputSerializer : kotlinx.serialization.KSerializer<SearchInput> {
    private val itemsSerializer = ListSerializer(ResponseItem.serializer())

    override val descriptor: SerialDescriptor = kotlinx.serialization.json.JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: SearchInput) {
        require(encoder is JsonEncoder) {
            "SearchInput can only be encoded as JSON."
        }
        val element = when (value) {
            is SearchInput.Text -> JsonPrimitive(value.text)
            is SearchInput.Items -> encoder.json.encodeToJsonElement(itemsSerializer, value.items)
        }
        encoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): SearchInput {
        require(decoder is JsonDecoder) {
            "SearchInput can only be decoded as JSON."
        }
        val element = decoder.decodeJsonElement()
        return when (element) {
            is JsonArray -> SearchInput.Items(decoder.json.decodeFromJsonElement(itemsSerializer, element))
            else -> SearchInput.Text(element.jsonPrimitive.contentOrNull.orEmpty())
        }
    }
}

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
    public val recency: Int? = null,
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
    public val lineno: Int? = null,
)

@Serializable
public data class ClickOperation(
    @SerialName("ref_id")
    public val refId: String,
    public val id: Int,
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
    public val pageno: Int,
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
    public val duration: Int? = null,
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
    public val numGames: Int? = null,
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
 * @property userLocation Nullable because location hints are optional; `null`
 * means send no location hint.
 * @property searchContextSize Nullable because context size is optional; `null`
 * means use the backend default.
 * @property filters Nullable because domain filters are optional; `null` means
 * send no filters.
 * @property imageSettings Nullable because image search settings are optional;
 * `null` means use backend defaults.
 * @property allowedCallers Nullable because caller filtering is optional; `null`
 * means do not restrict callers.
 * @property externalWebAccess Nullable because live-web control is optional;
 * `null` means use the backend default.
 */
@Serializable
public data class SearchSettings(
    @SerialName("user_location")
    public val userLocation: ApproximateLocation? = null,
    @SerialName("search_context_size")
    public val searchContextSize: SearchContextSize? = null,
    public val filters: SearchFilters? = null,
    @SerialName("image_settings")
    public val imageSettings: SearchImageSettings? = null,
    @SerialName("allowed_callers")
    public val allowedCallers: List<AllowedCaller>? = null,
    @SerialName("external_web_access")
    public val externalWebAccess: Boolean? = null,
)

/**
 * @property country Nullable because approximate locations may be partial;
 * `null` means the country is unknown or intentionally omitted.
 * @property region Nullable because approximate locations may be partial;
 * `null` means the region is unknown or intentionally omitted.
 * @property city Nullable because approximate locations may be partial; `null`
 * means the city is unknown or intentionally omitted.
 * @property timezone Nullable because approximate locations may be partial;
 * `null` means the timezone is unknown or intentionally omitted.
 */
@Serializable
public data class ApproximateLocation(
    public val type: LocationType = LocationType.Approximate,
    public val country: String? = null,
    public val region: String? = null,
    public val city: String? = null,
    public val timezone: String? = null,
)

@Serializable
public enum class LocationType {
    @SerialName("approximate")
    Approximate,
}

@Serializable
public enum class SearchContextSize {
    @SerialName("low")
    Low,

    @SerialName("medium")
    Medium,

    @SerialName("high")
    High,
}

/**
 * @property allowedDomains Nullable because allow-list filtering is optional;
 * `null` means no allow-list is sent.
 * @property blockedDomains Nullable because block-list filtering is optional;
 * `null` means no block-list is sent.
 */
@Serializable
public data class SearchFilters(
    @SerialName("allowed_domains")
    public val allowedDomains: List<String>? = null,
    @SerialName("blocked_domains")
    public val blockedDomains: List<String>? = null,
)

/**
 * @property maxResults Nullable because image result count is optional; `null`
 * means use the backend default.
 * @property caption Nullable because caption generation is optional; `null`
 * means use the backend default.
 */
@Serializable
public data class SearchImageSettings(
    @SerialName("max_results")
    public val maxResults: Int? = null,
    public val caption: Boolean? = null,
)

@Serializable
public enum class AllowedCaller {
    @SerialName("direct")
    Direct,

    @SerialName("shell")
    Shell,

    @SerialName("code_interpreter")
    CodeInterpreter,
}

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
