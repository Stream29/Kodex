package io.github.stream29.codex.lite.tool.websearch

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

private val SearchQuerySchema: ObjectPropertyDefinition =
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

private val OpenOperationSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("ref_id") {
            required = true
            string { description = "Reference id or URL to open." }
        }
        property("lineno") { integer { description = "Line number to position the page at." } }
    }

private val ClickOperationSchema: ObjectPropertyDefinition =
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

private val FindOperationSchema: ObjectPropertyDefinition =
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

private val ScreenshotOperationSchema: ObjectPropertyDefinition =
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

private val FinanceOperationSchema: ObjectPropertyDefinition =
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

private val WeatherOperationSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("location") {
            required = true
            string { description = "Location in Country, Area, City format." }
        }
        property("start") { string { description = "Start date in YYYY-MM-DD format." } }
        property("duration") { integer { description = "Number of days to return." } }
    }

private val SportsOperationSchema: ObjectPropertyDefinition =
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

private val TimeOperationSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("utc_offset") {
            required = true
            string { description = "UTC offset formatted like +03:00." }
        }
    }

public val SearchCommandsSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("search_query") {
            array {
                description = "Query the internet search engine."
                items { SearchQuerySchema }
            }
        }
        property("image_query") {
            array {
                description = "Query the image search engine."
                items { SearchQuerySchema }
            }
        }
        property("open") { array { items { OpenOperationSchema } } }
        property("click") { array { items { ClickOperationSchema } } }
        property("find") { array { items { FindOperationSchema } } }
        property("screenshot") { array { items { ScreenshotOperationSchema } } }
        property("finance") { array { items { FinanceOperationSchema } } }
        property("weather") { array { items { WeatherOperationSchema } } }
        property("sports") { array { items { SportsOperationSchema } } }
        property("time") { array { items { TimeOperationSchema } } }
        property("response_length") { string { enum = listOf("short", "medium", "long") } }
    }
