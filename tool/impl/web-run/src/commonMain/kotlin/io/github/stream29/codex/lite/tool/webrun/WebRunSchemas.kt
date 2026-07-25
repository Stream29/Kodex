package io.github.stream29.codex.lite.tool.webrun

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

private val ClickOperationSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        property("id") {
            required = true
            integer { description = "Numbered link id to open." }
        }
        property("ref_id") {
            required = true
            string { description = "Reference id containing the numbered link." }
        }
    }

private val FinanceOperationSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        property("market") {
            string { description = "ISO 3166-1 alpha-3 country code, \"OTC\", or \"\" for cryptocurrency." }
        }
        property("ticker") {
            required = true
            string { description = "Ticker symbol to look up." }
        }
        property("type") {
            required = true
            string {
                description = "Asset type to look up."
                enum = listOf("equity", "fund", "crypto", "index")
            }
        }
    }

private val FindOperationSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        property("pattern") {
            required = true
            string { description = "Text pattern to find." }
        }
        property("ref_id") {
            required = true
            string { description = "Reference id or URL to search within." }
        }
    }

private val SearchQuerySchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        property("domains") {
            array {
                description = "Whether to filter by a specific list of domains."
                ofString()
            }
        }
        property("q") {
            required = true
            string { description = "Search query." }
        }
        property("recency") {
            integer { description = "Whether to filter by recency, as a number of recent days." }
        }
    }

private val OpenOperationSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        property("lineno") {
            integer { description = "Line number to position the page at." }
        }
        property("ref_id") {
            required = true
            string { description = "Reference id or URL to open." }
        }
    }

private val ScreenshotOperationSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        property("pageno") {
            required = true
            integer { description = "Zero-indexed PDF page number." }
        }
        property("ref_id") {
            required = true
            string { description = "Reference id or URL to screenshot." }
        }
    }

private val SportsOperationSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        property("date_from") {
            string { description = "Start date in YYYY-MM-DD format." }
        }
        property("date_to") {
            string { description = "End date in YYYY-MM-DD format." }
        }
        property("fn") {
            required = true
            string {
                description = "Sports function to call."
                enum = listOf("schedule", "standings")
            }
        }
        property("league") {
            required = true
            string {
                description = "League to look up."
                enum = listOf("nba", "wnba", "nfl", "nhl", "mlb", "epl", "ncaamb", "ncaawb", "ipl")
            }
        }
        property("locale") {
            string { description = "Locale for the lookup." }
        }
        property("num_games") {
            integer { description = "Number of games to return." }
        }
        property("opponent") {
            string { description = "Opponent to use with `team` when narrowing the lookup." }
        }
        property("team") {
            string { description = "Team to look up, using the common 3 or 4 letter alias used in broadcasts." }
        }
        property("tool") {
            string {
                description = "Tool name for sports requests."
                enum = listOf("sports")
            }
        }
    }

private val TimeOperationSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        property("utc_offset") {
            required = true
            string { description = "UTC offset formatted like \"+03:00\"." }
        }
    }

private val WeatherOperationSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        property("duration") {
            integer { description = "Number of days to return. Defaults to 7." }
        }
        property("location") {
            required = true
            string { description = "Location in \"Country, Area, City\" format." }
        }
        property("start") {
            string { description = "Start date in YYYY-MM-DD format. Defaults to today." }
        }
    }

/** JSON schema for the `web.run` command group, aligned with Codex's reserved tool schema. */
public val WebRunParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        property("click") {
            array {
                description = "Open links from previously opened pages."
                items { ClickOperationSchema }
            }
        }
        property("finance") {
            array {
                description = "Look up prices for the given stock symbols."
                items { FinanceOperationSchema }
            }
        }
        property("find") {
            array {
                description = "Find text patterns in pages."
                items { FindOperationSchema }
            }
        }
        property("image_query") {
            array {
                description = "Query the image search engine for a given list of queries."
                items { SearchQuerySchema }
            }
        }
        property("open") {
            array {
                description = "Open pages by reference id or URL."
                items { OpenOperationSchema }
            }
        }
        property("response_length") {
            string {
                description = "Set the length of the response to be returned."
                enum = listOf("short", "medium", "long")
            }
        }
        property("screenshot") {
            array {
                description = "Take screenshots of PDF pages."
                items { ScreenshotOperationSchema }
            }
        }
        property("search_query") {
            array {
                description = "Query the internet search engine for a given list of queries."
                items { SearchQuerySchema }
            }
        }
        property("sports") {
            array {
                description = "Look up sports schedules and standings."
                items { SportsOperationSchema }
            }
        }
        property("time") {
            array {
                description = "Get time for the given UTC offsets."
                items { TimeOperationSchema }
            }
        }
        property("weather") {
            array {
                description = "Look up weather forecasts."
                items { WeatherOperationSchema }
            }
        }
    }
