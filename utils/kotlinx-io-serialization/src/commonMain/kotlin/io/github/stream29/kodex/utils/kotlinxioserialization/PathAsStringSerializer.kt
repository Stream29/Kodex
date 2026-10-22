@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.utils.kotlinxioserialization

import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

public actual object PathAsStringSerializer : KSerializer<Path> {
    public actual override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlinx.io.files.Path", PrimitiveKind.STRING)

    public actual override fun serialize(encoder: Encoder, value: Path) {
        encoder.encodeString(value.toString())
    }

    public actual override fun deserialize(decoder: Decoder): Path =
        Path(decoder.decodeString())
}
