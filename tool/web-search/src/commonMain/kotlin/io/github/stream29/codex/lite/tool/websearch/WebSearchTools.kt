package io.github.stream29.codex.lite.tool.websearch

import io.github.stream29.codex.lite.llmprovider.LlmNamespaceTool
import io.github.stream29.codex.lite.llmprovider.LlmTool
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

public object WebSearchTools {
    public const val Namespace: String = "web"
    public const val RunToolName: String = "run"

    public fun runTool(description: String = DefaultRunDescription): LlmTool =
        LlmTool.Namespace(
            name = Namespace,
            description = "Tools for accessing the internet.",
            tools = listOf(
                LlmNamespaceTool.Function(
                    name = RunToolName,
                    description = description,
                    strict = false,
                    parameters = searchCommandsSchema(),
                ),
            ),
        )

    public const val DefaultRunDescription: String =
        "Search, open, inspect, and retrieve concise current information from the web."
}

public fun searchCommandsSchema(): ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("search_query") {
            array {
                description = "Query the internet search engine."
                items { searchQuerySchema() }
            }
        }
        property("image_query") {
            array {
                description = "Query the image search engine."
                items { searchQuerySchema() }
            }
        }
        property("open") { array { items { openOperationSchema() } } }
        property("click") { array { items { clickOperationSchema() } } }
        property("find") { array { items { findOperationSchema() } } }
        property("screenshot") { array { items { screenshotOperationSchema() } } }
        property("finance") { array { items { financeOperationSchema() } } }
        property("weather") { array { items { weatherOperationSchema() } } }
        property("sports") { array { items { sportsOperationSchema() } } }
        property("time") { array { items { timeOperationSchema() } } }
        property("response_length") { string { enum = listOf("short", "medium", "long") } }
    }

private fun searchQuerySchema(): ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("q") {
            required = true
            string { description = "Search query." }
        }
        property("recency") { integer { description = "Filter by number of recent days." } }
        property("domains") {
            array {
                description = "Restrict results to domains."
                ofString()
            }
        }
    }

private fun openOperationSchema(): ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("ref_id") {
            required = true
            string { description = "Reference id or URL to open." }
        }
        property("lineno") { integer { description = "Line number to position the page at." } }
    }

private fun clickOperationSchema(): ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("ref_id") {
            required = true
            string { description = "Reference id containing the numbered link." }
        }
        property("id") {
            required = true
            integer { description = "Numbered link id to open." }
        }
    }

private fun findOperationSchema(): ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("ref_id") {
            required = true
            string { description = "Reference id or URL to search within." }
        }
        property("pattern") {
            required = true
            string { description = "Text pattern to find." }
        }
    }

private fun screenshotOperationSchema(): ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("ref_id") {
            required = true
            string { description = "Reference id or URL to screenshot." }
        }
        property("pageno") {
            required = true
            integer { description = "Zero-indexed PDF page number." }
        }
    }

private fun financeOperationSchema(): ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("ticker") {
            required = true
            string { description = "Ticker symbol to look up." }
        }
        property("type") {
            required = true
            string { enum = listOf("equity", "fund", "crypto", "index") }
        }
        property("market") { string { description = "ISO 3166-1 alpha-3 country code, OTC, or empty for crypto." } }
    }

private fun weatherOperationSchema(): ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("location") {
            required = true
            string { description = "Location in Country, Area, City format." }
        }
        property("start") { string { description = "Start date in YYYY-MM-DD format." } }
        property("duration") { integer { description = "Number of days to return." } }
    }

private fun sportsOperationSchema(): ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("tool") { string { enum = listOf("sports") } }
        property("fn") {
            required = true
            string { enum = listOf("schedule", "standings") }
        }
        property("league") {
            required = true
            string { enum = listOf("nba", "wnba", "nfl", "nhl", "mlb", "epl", "ncaamb", "ncaawb", "ipl") }
        }
        property("team") { string { description = "Common 3 or 4 letter team alias." } }
        property("opponent") { string { description = "Opponent alias." } }
        property("date_from") { string { description = "Start date in YYYY-MM-DD format." } }
        property("date_to") { string { description = "End date in YYYY-MM-DD format." } }
        property("num_games") { integer { description = "Number of games to return." } }
        property("locale") { string { description = "Locale for the lookup." } }
    }

private fun timeOperationSchema(): ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("utc_offset") {
            required = true
            string { description = "UTC offset formatted like +03:00." }
        }
    }
