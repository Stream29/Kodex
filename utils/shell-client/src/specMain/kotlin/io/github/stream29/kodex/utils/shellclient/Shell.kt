package io.github.stream29.kodex.utils.shellclient

import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A shell executable with a recognized command-line syntax.
 *
 * [path] is preserved when a model explicitly selects a shell binary. The
 * serializer presents this object as that single path string in tool JSON.
 */
@Serializable(with = Shell.Serializer::class)
public data class Shell(
    public val type: ShellType,
    public val path: Path,
) {
    public companion object {}

    /** Presents [Shell] as the model-facing shell path string. */
    public object Serializer : KSerializer<Shell> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Shell", PrimitiveKind.STRING)

        /**
         * Decodes the executable path without host discovery or normalization.
         *
         * @throws SerializationException when the path has no supported shell syntax.
         */
        override fun deserialize(decoder: Decoder): Shell {
            val path = decoder.decodeString()
            val type = path.shellTypeOrNull()
                ?: throw SerializationException("Unsupported shell: `$path`.")
            return Shell(type = type, path = Path(path))
        }

        override fun serialize(encoder: Encoder, value: Shell) {
            encoder.encodeString(value.path.toString())
        }
    }
}

/** Command-line syntax understood by a [Shell]. */
public enum class ShellType {
    Sh,
    Bash,
    Zsh,
    PowerShell,
    Cmd,
}

/** Recognizes command-line syntax from a path, without querying the host filesystem. */
public fun String.shellTypeOrNull(): ShellType? {
    val fileName = substringAfterLast('/').substringAfterLast('\\')
    return when (fileName.substringBeforeLast('.', missingDelimiterValue = fileName).lowercase()) {
        "sh" -> ShellType.Sh
        "bash" -> ShellType.Bash
        "zsh" -> ShellType.Zsh
        "powershell", "pwsh" -> ShellType.PowerShell
        "cmd" -> ShellType.Cmd
        else -> null
    }
}
