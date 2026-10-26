package io.github.stream29.kodex.backend

private val originPattern = Regex("""http://(127\.0\.0\.1|localhost)(?::([1-9][0-9]{0,4}))?""")

public actual fun canonicalLocalBrowserOrigin(value: String): String {
    val match = requireNotNull(originPattern.matchEntire(value)) { "Use a loopback HTTP origin without a path." }
    val port = match.groupValues[2].takeIf(String::isNotEmpty)?.toInt() ?: 80
    require(port in 1..65535) { "Invalid browser origin port." }
    return "http://${match.groupValues[1]}" + if (port == 80) "" else ":$port"
}
