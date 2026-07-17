package io.github.stream29.codex.lite.tool.webrun

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

/** JSON schema for the `web.run` command group. */
public val WebRunParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("search_query") {
            array {
                description = "Search the internet for text queries."
                maxItems = 4
                ofObject {
                    additionalProperties = false
                    property("q") {
                        required = true
                        string { description = "Text query." }
                    }
                    property("recency") {
                        integer { description = "Optional maximum age in days." }
                    }
                    property("domains") {
                        array {
                            description = "Optional domain allow-list."
                            ofString()
                        }
                    }
                }
            }
        }
        property("image_query") {
            array {
                description = "Search the image index for text queries."
                ofObject {
                    additionalProperties = false
                    property("q") {
                        required = true
                        string { description = "Image query." }
                    }
                    property("recency") {
                        integer { description = "Optional maximum age in days." }
                    }
                    property("domains") {
                        array {
                            description = "Optional domain allow-list."
                            ofString()
                        }
                    }
                }
            }
        }
        property("open") {
            array {
                description = "Open result references or URLs."
                ofObject {
                    additionalProperties = false
                    property("ref_id") {
                        required = true
                        string { description = "Search result reference or URL." }
                    }
                    property("lineno") {
                        integer { description = "Optional line number to position the page at." }
                    }
                }
            }
        }
        property("click") {
            array {
                description = "Open a numbered link from an opened page."
                ofObject {
                    additionalProperties = false
                    property("ref_id") {
                        required = true
                        string { description = "Opened page reference." }
                    }
                    property("id") {
                        required = true
                        integer { description = "Link identifier." }
                    }
                }
            }
        }
        property("find") {
            array {
                description = "Find text in an opened page."
                ofObject {
                    additionalProperties = false
                    property("ref_id") {
                        required = true
                        string { description = "Opened page reference." }
                    }
                    property("pattern") {
                        required = true
                        string { description = "Text pattern to find." }
                    }
                }
            }
        }
        property("screenshot") {
            array {
                description = "Capture a page from an opened PDF."
                ofObject {
                    additionalProperties = false
                    property("ref_id") {
                        required = true
                        string { description = "Opened PDF reference." }
                    }
                    property("pageno") {
                        required = true
                        integer { description = "Zero-indexed PDF page number." }
                    }
                }
            }
        }
        property("finance") {
            array {
                description = "Look up financial instrument prices."
                ofObject {
                    additionalProperties = false
                    property("ticker") {
                        required = true
                        string { description = "Ticker symbol." }
                    }
                    property("type") {
                        required = true
                        string {
                            description = "Asset type."
                            enum = listOf("equity", "fund", "crypto", "index")
                        }
                    }
                    property("market") {
                        string { description = "Optional ISO market or cryptocurrency market marker." }
                    }
                }
            }
        }
        property("weather") {
            array {
                description = "Look up weather forecasts."
                ofObject {
                    additionalProperties = false
                    property("location") {
                        required = true
                        string { description = "Location in Country, Area, City form." }
                    }
                    property("start") {
                        string { description = "Optional start date in YYYY-MM-DD format." }
                    }
                    property("duration") {
                        integer { description = "Optional forecast duration in days." }
                    }
                }
            }
        }
        property("sports") {
            array {
                description = "Look up sports schedules and standings."
                ofObject {
                    additionalProperties = false
                    property("tool") {
                        string {
                            description = "Optional legacy sports tool discriminator."
                            enum = listOf("sports")
                        }
                    }
                    property("fn") {
                        required = true
                        string {
                            description = "Sports operation."
                            enum = listOf("schedule", "standings")
                        }
                    }
                    property("league") {
                        required = true
                        string {
                            description = "League identifier."
                            enum = listOf("nba", "wnba", "nfl", "nhl", "mlb", "epl", "ncaamb", "ncaawb", "ipl")
                        }
                    }
                    property("team") {
                        string { description = "Optional team broadcast alias." }
                    }
                    property("opponent") {
                        string { description = "Optional opponent broadcast alias." }
                    }
                    property("date_from") {
                        string { description = "Optional lower date bound in YYYY-MM-DD format." }
                    }
                    property("date_to") {
                        string { description = "Optional upper date bound in YYYY-MM-DD format." }
                    }
                    property("num_games") {
                        integer { description = "Optional maximum number of games." }
                    }
                    property("locale") {
                        string { description = "Optional locale." }
                    }
                }
            }
        }
        property("time") {
            array {
                description = "Look up time for UTC offsets."
                ofObject {
                    additionalProperties = false
                    property("utc_offset") {
                        required = true
                        string { description = "UTC offset formatted as +03:00." }
                    }
                }
            }
        }
        property("response_length") {
            string {
                description = "Requested response length."
                enum = listOf("short", "medium", "long")
            }
        }
    }
