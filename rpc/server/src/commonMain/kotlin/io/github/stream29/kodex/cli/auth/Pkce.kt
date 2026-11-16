package io.github.stream29.kodex.cli.auth

import io.github.stream29.kodex.utils.oauthloopback.pkceS256CodeChallenge

internal fun pkceCodeChallenge(codeVerifier: String): String =
    pkceS256CodeChallenge(codeVerifier)
