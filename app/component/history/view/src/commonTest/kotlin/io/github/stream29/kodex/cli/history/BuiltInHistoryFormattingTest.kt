package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.*
import kotlinx.serialization.json.Json
import kotlin.test.*

val builtInHistoryFormattingTest by testSuite {
    test("recorded image title uses only a filename and explicit references exclude opaque payloads") {
        assertEquals("Generate result.png", recordedImageTitle("C:\\images\\result.png"))
        assertEquals("Generate image", recordedImageTitle(null))
        assertEquals("Generate image", recordedImageTitle("/"))
        assertNull("aGVsbG8=".readableHostedImageReference())
        assertNull("data:image/png;base64,aGVsbG8=".readableHostedImageReference())
        assertEquals("https://example.test/a.png", "https://example.test/a.png".readableHostedImageReference())
    }
    test("hosted Web retains all queries and explicit unknown actions have no invented operations") {
        assertEquals(listOf("Search first", "Search second", "Search third"),
            WebSearchAction.Search("first", listOf("second", "third")).operationTitles())
        assertTrue(WebSearchAction.Other.operationTitles().isEmpty())
        assertTrue(WebSearchAction.OpenPage().operationTitles().isEmpty())
    }
    test("Web operation formatting keeps every qualifier including explicit zero values") {
        val commands = Json.decodeFromString<SearchCommands>("""{
          "search_query":[{"q":"alpha","domains":["a.test","b.test"],"recency":0},{"q":"beta"}],
          "image_query":[{"q":"gamma","domains":["img.test"],"recency":3}],
          "open":[{"ref_id":"ref","lineno":0}],"click":[{"ref_id":"ref","id":7}],
          "find":[{"ref_id":"ref","pattern":"needle"}],"screenshot":[{"ref_id":"ref","pageno":0}],
          "finance":[{"ticker":"ABC","type":"equity","market":"USA"}],
          "weather":[{"location":"Oslo","start":"2026-10-12","duration":0}],
          "sports":[{"tool":"sports","fn":"schedule","league":"nba","team":"GSW","opponent":"NYK",
            "date_from":"2026-10-12","date_to":"2026-10-13","num_games":0,"locale":"en-US"}],
          "time":[{"utc_offset":"+08:00"}],"response_length":"long"
        }""")
        assertEquals(listOf(
            "Search alpha; domains=a.test, b.test; recency=0", "Search beta",
            "Search images gamma; domains=img.test; recency=3", "Open ref at line 0",
            "Click link 7 in ref", "Find \"needle\" in ref", "Screenshot ref page 0",
            "Finance ABC; type=equity; market=USA", "Weather Oslo; start=2026-10-12; duration=0",
            "Sports nba schedule; tool=sports; team=GSW; opponent=NYK; date_from=2026-10-12; date_to=2026-10-13; num_games=0; locale=en-US",
            "Time +08:00",
        ), commands.operationTitles())
    }
}
