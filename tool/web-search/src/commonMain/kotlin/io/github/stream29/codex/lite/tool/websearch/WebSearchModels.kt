package io.github.stream29.codex.lite.tool.websearch

import io.github.stream29.codex.lite.llmprovider.Reasoning
import io.github.stream29.codex.lite.llmprovider.ResponseItem
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

@Serializable
public data class SearchRequest(
    public val id: String,
    public val model: String,
    public val reasoning: Reasoning? = null,
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

@Serializable
public data class SearchQuery(
    public val q: String,
    public val recency: Int? = null,
    public val domains: List<String>? = null,
)

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

@Serializable
public data class WeatherOperation(
    public val location: String,
    public val start: String? = null,
    public val duration: Int? = null,
)

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

@Serializable
public data class SearchFilters(
    @SerialName("allowed_domains")
    public val allowedDomains: List<String>? = null,
    @SerialName("blocked_domains")
    public val blockedDomains: List<String>? = null,
)

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

@Serializable
public data class SearchResponse(
    @SerialName("encrypted_output")
    public val encryptedOutput: String? = null,
    public val output: String,
)
