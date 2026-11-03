package io.github.stream29.kodex.backend

import kotlinx.serialization.json.Json

/**
 * Shared strict codec for host/pairing/bootstrap DTOs: explicit defaults/nulls, original generated
 * serializers and "type" discriminator. Unknown keys/types, lenient syntax and coerced values are
 * rejected. Adapters must separately enforce byte/queue/resource budgets before decoding/admitting;
 * this codec is not an authentication, URL validation or resource-lifecycle implementation.
 */
public expect val LocalFrontendHostJson: Json
