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
    public companion object {
        /** The host's dynamically resolved shell used when no shell is requested. */
        public val default: Shell
            get() = resolveDefaultShell()

        /**
         * Finds an installed executable for [type].
         *
         * Resolution follows the host's explicit preference, `PATH`, and
         * platform fallback locations. It does not affect a [Shell] decoded
         * from model input: that path is intentionally preserved as supplied.
         *
         * @param preferredPath Nullable when the caller has not selected a
         * specific executable; `null` starts host discovery directly.
         * @return Nullable when this host has no matching executable; `null`
         * means the requested shell cannot be started on this host.
         */
        public fun resolve(
            type: ShellType,
            preferredPath: Path? = null,
        ): Shell? =
            resolveShell(type, preferredPath)
    }

    /** Presents [Shell] as the model-facing shell path string. */
    public object Serializer : KSerializer<Shell> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Shell", PrimitiveKind.STRING)

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

internal fun ShellType.argumentsBeforeCommand(login: Boolean): List<String> =
    when (this) {
        ShellType.Sh, ShellType.Bash, ShellType.Zsh -> listOf(if (login) "-lc" else "-c")
        ShellType.PowerShell -> buildList {
            if (!login) add("-NoProfile")
            add("-Command")
        }

        ShellType.Cmd -> listOf("/d", "/s", "/c")
    }

internal enum class ShellHostPlatform {
    Windows,
    Macos,
    Linux,
}

internal expect val shellHostPlatform: ShellHostPlatform

internal data class ShellInvocation(
    val executable: String,
    val argumentsBeforeCommand: List<String>,
    val command: String,
)

internal fun String.shellTypeOrNull(): ShellType? {
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
