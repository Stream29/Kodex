package io.github.stream29.kodex.app.migration.v0_4_7

import com.charleskorn.kaml.*
import kotlinx.io.files.Path
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * Frozen 0.4.6 -> 0.4.7 file schemas. No current settings, MCP, Hook, OpenAI or shell
 * model is used here. YAML nodes keep credential strings out of diagnostic data classes.
 * Missing shell means host discovery, not the shell installed on the migration builder.
 */
internal object SettingsCodec {
    private val yaml = Yaml(configuration = YamlConfiguration(
        strictMode = false, polymorphismStyle = PolymorphismStyle.Property,
        polymorphismPropertyName = "type", singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous,
    ))

    fun parse(text: String): YamlMap = yaml.parseToYamlNode(text) as? YamlMap
        ?: error("Settings must be a mapping.")

    fun encode(value: YamlMap): String = yaml.encodeToString(FrozenNodeWriter, value) + "\n"

    fun legacy(root: YamlMap): Pair<YamlMap, YamlMap> {
        // Even discarded known fields are checked before the source can be deleted.
        val sidebars = root.mapping("sidebars", legacy = true)
        listOf("left_width", "right_width").forEach { key ->
            val width = sidebars.node(key, legacy = true)
            if (width != null) require((width as? YamlScalar)?.toInt()?.let { it >= 4 } == true)
        }
        root.mapping("hooks", legacy = true).entries.forEach { (name, body) ->
            require(name.content.isNotBlank())
            val hook = body.mapping()
            hook.text("type").oneOf(
                "pre_tool_use", "post_tool_use", "user_prompt_submit", "stop",
                "pre_compact", "post_compact", "unhandled_error",
            )
            require(hook.text("command").isNotBlank())
        }
        return backend(root, legacy = true) to frontend(root, legacy = true)
    }

    fun backend(root: YamlMap, legacy: Boolean = false): YamlMap {
        val context = root.mapping("context_sources", legacy)
        val defaults = root.mapping("new_session", legacy)
        val title = root.mapping("session_title", legacy)
        val shell = root.node("shell", legacy)?.let {
            val path = it.text()
            val fileName = path.substringAfterLast('/').substringAfterLast('\\')
            fileName.substringBeforeLast('.', fileName).lowercase().oneOf("sh", "bash", "zsh", "powershell", "pwsh", "cmd")
            text(Path(path).toString())
        }
        val customSources = context.sequence("custom_sources", legacy).map { item ->
            val value = item.mapping()
            val path = value.text("path").also { require(it.isNotBlank()) }
            mapping("path" to text(path), "enabled" to value.boolean("enabled", true))
        }
        val model = defaults.text("model", "gpt-5.6-sol", legacy).also { require(it.isNotBlank()) }
        val titleModel = title.node("model", legacy)?.let {
            if (it is YamlNull) nil() else text(it.text().also { value -> require(value.isNotBlank()) })
        } ?: nil()
        return mapping(
            "auth_source" to text(root.text("auth_source", "codex", legacy).oneOf("codex", "kodex")),
            "shell" to shell,
            "context_sources" to mapping(
                "agents_home" to context.boolean("agents_home", true, legacy),
                "kodex_home" to context.boolean("kodex_home", true, legacy),
                "codex_home" to context.boolean("codex_home", true, legacy),
                "git_root" to context.boolean("git_root", true, legacy),
                "working_directory" to context.boolean("working_directory", true, legacy),
                "custom_sources" to sequence(customSources),
            ),
            "new_session" to mapping(
                "model" to text(model),
                "reasoning_effort" to text(effort(defaults.text("reasoning_effort", "medium", legacy))),
                "service_tier" to text(defaults.text("service_tier", "default", legacy)
                    .oneOf("default", "fast", "priority", "flex").let { if (it == "fast") "priority" else it }),
                "request_user_input_mode" to text(defaults.text("request_user_input_mode", "ask_user", legacy)
                    .oneOf("ask_user", "no_question")),
            ),
            "session_title" to mapping(
                "enabled" to title.boolean("enabled", true, legacy), "model" to titleModel,
                "reasoning_effort" to text(effort(title.text("reasoning_effort", "low", legacy))),
            ),
            "mcp_servers" to mapping(root.mapping("mcp_servers", legacy).entries.map { (key, value) ->
                key.content to server(value.mapping())
            }),
        )
    }

    fun frontend(root: YamlMap, legacy: Boolean = false): YamlMap {
        val sidebars = root.mapping("sidebars", legacy)
        val names = mutableSetOf<String>()
        val hooks = if (legacy) emptyList() else root.sequence("hooks").map { item ->
            val value = item.mapping()
            val name = value.text("name")
            require(name.isNotBlank() && names.add(name))
            val command = value.text("command").also { require(it.isNotBlank()) }
            val types = value.sequence("types").map {
                it.text().oneOf("stop_assistant_message", "stop_request_user_input", "stop_suggest_subagent", "stop_unhandled_error")
            }.distinct().sorted()
            require(types.isNotEmpty())
            mapping("name" to text(name), "types" to sequence(types.map(::text)), "command" to text(command))
        }
        return mapping(
            "new_line_key" to text(root.text("new_line_key", "shift_enter", legacy).oneOf("shift_enter", "enter")),
            "sidebars" to mapping(
                "left" to text(sidebars.text("left", "history_index", legacy).oneOf("none", "history_index", "terminal_sessions")),
                "right" to text(sidebars.text("right", "terminal_sessions", legacy).oneOf("none", "history_index", "terminal_sessions")),
            ),
            "hooks" to sequence(hooks),
        )
    }

    private fun server(root: YamlMap): YamlMap = when (root.text("type")) {
        "streamable_http" -> mapping(
            "type" to text("streamable_http"),
            "url" to text(root.text("url").also { require(it.isNotBlank()) }),
            "headers" to strings(root.mapping("headers")),
            "oauth" to (root.node("oauth")?.let { if (it is YamlNull) nil() else oauth(it.mapping()) } ?: nil()),
            "enabled" to root.boolean("enabled", true),
        )
        "stdio" -> mapping(
            "type" to text("stdio"),
            "command" to text(root.text("command").also { require(it.isNotBlank()) }),
            "args" to sequence(root.sequence("args").map { text(it.text()) }),
            "environment" to strings(root.mapping("environment")),
            "working_directory" to text(Path(root.text("working_directory", ".")).toString()),
            "enabled" to root.boolean("enabled", true),
        )
        else -> error("Unsupported MCP configuration type.")
    }

    private fun oauth(root: YamlMap): YamlMap {
        val type = root.text("type").oneOf("uninitialized", "initialized")
        val client = requireNotNull(root.node("client")).mapping()
        val id = client.optionalText("client_id")
        val secret = client.optionalText("client_secret")
        require(id == null || id.isNotBlank())
        require(id != null || secret == null)
        val common = listOf(
            "type" to text(type),
            "client" to mapping(
                "client_id" to (id?.let(::text) ?: nil()),
                "client_secret" to (secret?.let(::text) ?: nil()),
                "redirect_uri" to text(client.text("redirect_uri", "http://127.0.0.1:8765/callback")
                    .also { require(it.isNotBlank()) }),
                "authorization_endpoint" to (client.optionalText("authorization_endpoint")?.let(::text) ?: nil()),
                "token_endpoint" to (client.optionalText("token_endpoint")?.let(::text) ?: nil()),
            ),
            "resource" to (root.optionalText("resource")?.let(::text) ?: nil()),
            "scopes" to sequence(root.sequence("scopes").map { text(it.text()) }),
        )
        if (type == "uninitialized") return mapping(common)
        require(id != null)
        val expires = root.node("expires_at_epoch_seconds")
        return mapping(common + listOf(
            "resolved_authorization_endpoint" to text(root.text("resolved_authorization_endpoint").also { require(it.isNotBlank()) }),
            "resolved_token_endpoint" to text(root.text("resolved_token_endpoint").also { require(it.isNotBlank()) }),
            "token_endpoint_auth_method" to text(root.text("token_endpoint_auth_method", "client_secret_post")
                .oneOf("client_secret_post", "client_secret_basic", "none")),
            "access_token" to text(root.text("access_token")),
            "refresh_token" to (root.optionalText("refresh_token")?.let(::text) ?: nil()),
            "token_type" to text(root.text("token_type", "Bearer").also { require(it.isNotBlank()) }),
            "expires_at_epoch_seconds" to if (expires == null || expires is YamlNull) nil()
                else text((expires as YamlScalar).toLong().toString()),
        ))
    }

    private fun strings(root: YamlMap): YamlMap =
        mapping(root.entries.map { (key, value) -> key.content to text(value.text()) })
    private fun effort(value: String): String = if (value == "ultra") "max" else value
    private fun String.oneOf(vararg allowed: String): String = also { require(it in allowed) { "Unsupported settings value." } }
    private fun YamlNode.mapping(): YamlMap = this as? YamlMap ?: error("Expected mapping.")
    private fun YamlNode.text(): String = (this as? YamlScalar)?.content ?: error("Expected scalar.")
    private fun YamlMap.node(name: String, legacy: Boolean = false): YamlNode? =
        get<YamlNode>(name).let { if (legacy && it is YamlNull) null else it }
    private fun YamlMap.mapping(name: String, legacy: Boolean = false): YamlMap =
        node(name, legacy)?.mapping() ?: mapping(emptyList())
    private fun YamlMap.text(name: String, default: String? = null, legacy: Boolean = false): String =
        node(name, legacy)?.text() ?: requireNotNull(default) { "A required settings field is missing." }
    private fun YamlMap.optionalText(name: String): String? =
        node(name)?.let { if (it is YamlNull) null else it.text() }
    private fun YamlMap.boolean(name: String, default: Boolean, legacy: Boolean = false): YamlScalar =
        SettingsCodec.text((node(name, legacy)?.let { (it as? YamlScalar)?.toBoolean() ?: error("Expected boolean.") } ?: default).toString())
    private fun YamlMap.sequence(name: String, legacy: Boolean = false): List<YamlNode> =
        node(name, legacy)?.let { (it as? YamlList)?.items ?: error("Expected list.") } ?: emptyList()
    private fun mapping(vararg fields: Pair<String, YamlNode?>): YamlMap = mapping(fields.toList())
    private fun mapping(fields: List<Pair<String, YamlNode?>>): YamlMap =
        YamlMap(fields.mapNotNull { (key, value) -> value?.let { text(key) to it } }.toMap(), YamlPath.root)
    private fun sequence(items: List<YamlNode>): YamlList = YamlList(items, YamlPath.root)
    private fun text(value: String): YamlScalar = YamlScalar(value, YamlPath.root)
    private fun nil(): YamlNull = YamlNull(YamlPath.root)
}

/**
 * Kaml's generic YamlNode serializer guesses primitive types: a string such as
 * "0001" becomes the number 1. Persist every normalized scalar as a string instead.
 * Frozen readers and the settings loaders accept quoted booleans/integers, while
 * names, paths, commands and credentials retain their exact scalar content.
 */
@OptIn(ExperimentalSerializationApi::class)
private object FrozenNodeWriter : KSerializer<YamlNode> {
    override val descriptor = buildClassSerialDescriptor("SettingsV0_4_7Node")
    override fun serialize(encoder: Encoder, value: YamlNode) {
        when (value) {
            is YamlMap -> encoder.encodeSerializableValue(
                MapSerializer(String.serializer(), this),
                value.entries.mapKeys { it.key.content },
            )
            is YamlList -> encoder.encodeSerializableValue(ListSerializer(this), value.items)
            is YamlScalar -> encoder.encodeString(value.content)
            is YamlNull -> encoder.encodeNull()
            else -> error("Only normalized settings nodes can be written.")
        }
    }
    override fun deserialize(decoder: Decoder): YamlNode = error("Use the frozen settings parser.")
}
