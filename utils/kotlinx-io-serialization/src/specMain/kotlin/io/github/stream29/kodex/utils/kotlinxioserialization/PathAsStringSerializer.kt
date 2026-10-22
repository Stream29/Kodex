@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.utils.kotlinxioserialization

import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Serializes a [Path] through its platform-independent string representation. */
public expect object PathAsStringSerializer : KSerializer<Path> {
    public override val descriptor: SerialDescriptor
    public override fun serialize(encoder: Encoder, value: Path)
    public override fun deserialize(decoder: Decoder): Path
}
