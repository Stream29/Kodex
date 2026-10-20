package io.github.stream29.kodex.app.migration.v0_5_2

internal const val LegacyWorkExecMissingYield: String =
    """{"type":"command_execution_tool_event","call_id":"call-exec-1","item_id":"item-exec-1","action":{"type":"exec_command","arguments":{"cmd":"echo hello","workdir":"/workspace","tty":false,"max_output_tokens":10000,"legacy_arg_extra":"keep-arg"}},"result":{"type":"output","value":{"chunk_id":"chunk-1","wall_time_seconds":0.12,"exit_code":0,"original_token_count":2,"output":"hello\n"}},"legacy_event_extra":"keep-event"}"""

internal const val MigratedWorkExecMissingYield: String =
    """{"type":"command_execution_tool_event","call_id":"call-exec-1","item_id":"item-exec-1","action":{"type":"exec_command","arguments":{"cmd":"echo hello","workdir":"/workspace","tty":false,"yield_time_ms":10000,"max_output_tokens":10000,"legacy_arg_extra":"keep-arg"}},"result":{"type":"output","value":{"chunk_id":"chunk-1","wall_time_seconds":0.12,"exit_code":0,"original_token_count":2,"output":"hello\n"}},"legacy_event_extra":"keep-event"}"""

internal const val LegacyWorkWriteEmptyCharsMissingYield: String =
    """{"type":"command_execution_tool_event","call_id":"call-write-poll","action":{"type":"write_stdin","arguments":{"session_id":7,"chars":"","max_output_tokens":10000}},"result":{"type":"output","value":{"chunk_id":"chunk-2","wall_time_seconds":5.0,"exit_code":0,"original_token_count":1,"output":"done\n"}}}"""

internal const val MigratedWorkWriteEmptyCharsMissingYield: String =
    """{"type":"command_execution_tool_event","call_id":"call-write-poll","action":{"type":"write_stdin","arguments":{"session_id":7,"chars":"","yield_time_ms":250,"max_output_tokens":10000}},"result":{"type":"output","value":{"chunk_id":"chunk-2","wall_time_seconds":5.0,"exit_code":0,"original_token_count":1,"output":"done\n"}}}"""

internal const val LegacyWorkWriteNonEmptyCharsMissingYield: String =
    """{"type":"command_execution_tool_event","call_id":"call-write-input","action":{"type":"write_stdin","arguments":{"session_id":7,"chars":"y\n","max_output_tokens":4096,"custom_meta":42}},"result":{"type":"failure","message":"Session 7 is not running."}}"""

internal const val MigratedWorkWriteNonEmptyCharsMissingYield: String =
    """{"type":"command_execution_tool_event","call_id":"call-write-input","action":{"type":"write_stdin","arguments":{"session_id":7,"chars":"y\n","yield_time_ms":250,"max_output_tokens":4096,"custom_meta":42}},"result":{"type":"failure","message":"Session 7 is not running."}}"""

/** Pretty-printed record that already has a negative yield_time_ms; bytes must remain untouched. */
internal const val PrettyWorkExecNegativeYield: String =
    "{\n  \"type\": \"command_execution_tool_event\",\n  \"call_id\": \"call-neg\",\n  \"action\": {\n    \"type\": \"exec_command\",\n    \"arguments\": {\n      \"cmd\": \"sleep 1\",\n      \"tty\": false,\n      \"yield_time_ms\": -1,\n      \"max_output_tokens\": 10000\n    }\n  },\n  \"result\": {\n    \"type\": \"failure\",\n    \"message\": \"`yield_time_ms` must not be negative.\"\n  }\n}"

/** Record with an out-of-range Long yield_time_ms; must not be clamped or rewritten. */
internal const val WorkWriteOutOfRangeYield: String =
    """{"type":"command_execution_tool_event","call_id":"call-huge","action":{"type":"write_stdin","arguments":{"session_id":9,"chars":"","yield_time_ms":9223372036854775807,"max_output_tokens":10000}},"result":{"type":"output","value":{"chunk_id":"chunk-9","wall_time_seconds":0.01,"exit_code":0,"original_token_count":0,"output":""}}}"""

/** Unrelated work tool event whose arguments happen to contain `cmd` without `yield_time_ms`. */
internal const val UnrelatedWorkTextToolEvent: String =
    """{"type":"text_tool_event","call_id":"call-text","name":"custom_runner","arguments":{"cmd":"echo untouched","session_id":1},"result":"OK","success":true}"""

/** Invalid/raw tool call for exec_command missing yield_time_ms; must remain untouched. */
internal const val WorkInvalidRawExecToolCall: String =
    """{"type":"invalid_tool_call","call_id":"call-invalid","invocation":{"type":"function","name":"exec_command","arguments":"{\"cmd\":\"echo raw\"}"},"message":"Invalid tool arguments"}"""

/** Unknown future work event containing nested action.arguments; must remain untouched. */
internal const val UnknownFutureWorkEvent: String =
    """{"type":"future_command_wrapper","call_id":"call-future","action":{"type":"exec_command","arguments":{"cmd":"echo future"}}}"""

internal const val LegacyUnstableMultiEventArray: String =
    """[{"type":"command_execution","call_id":"pending-exec","item_id":"item-1","action":{"type":"exec_command","arguments":{"cmd":"./gradlew check","tty":true,"max_output_tokens":10000,"extra_flag":true}}},{"type":"command_execution","call_id":"pending-poll","action":{"type":"write_stdin","arguments":{"session_id":11,"chars":"","max_output_tokens":10000}}},{"type":"command_execution","call_id":"pending-write","action":{"type":"write_stdin","arguments":{"session_id":11,"chars":"quit\n"}}},{"type":"command_execution","call_id":"pending-neg","action":{"type":"write_stdin","arguments":{"session_id":11,"chars":"","yield_time_ms":-250,"max_output_tokens":10000}}},{"type":"command_execution","call_id":"pending-huge","action":{"type":"exec_command","arguments":{"cmd":"sleep 60","tty":false,"yield_time_ms":900000,"max_output_tokens":10000}}},{"type":"function","call_id":"pending-fn","name":"other_tool","arguments":{"cmd":"leave-alone"}},{"type":"invalid_tool_call","call_id":"pending-invalid","invocation":{"type":"function","name":"write_stdin","arguments":"{\"session_id\":11}"},"message":"Rejected"}]"""

internal const val MigratedUnstableMultiEventArray: String =
    """[{"type":"command_execution","call_id":"pending-exec","item_id":"item-1","action":{"type":"exec_command","arguments":{"cmd":"./gradlew check","tty":true,"yield_time_ms":10000,"max_output_tokens":10000,"extra_flag":true}}},{"type":"command_execution","call_id":"pending-poll","action":{"type":"write_stdin","arguments":{"session_id":11,"chars":"","yield_time_ms":250,"max_output_tokens":10000}}},{"type":"command_execution","call_id":"pending-write","action":{"type":"write_stdin","arguments":{"session_id":11,"chars":"quit\n","yield_time_ms":250}}},{"type":"command_execution","call_id":"pending-neg","action":{"type":"write_stdin","arguments":{"session_id":11,"chars":"","yield_time_ms":-250,"max_output_tokens":10000}}},{"type":"command_execution","call_id":"pending-huge","action":{"type":"exec_command","arguments":{"cmd":"sleep 60","tty":false,"yield_time_ms":900000,"max_output_tokens":10000}}},{"type":"function","call_id":"pending-fn","name":"other_tool","arguments":{"cmd":"leave-alone"}},{"type":"invalid_tool_call","call_id":"pending-invalid","invocation":{"type":"function","name":"write_stdin","arguments":"{\"session_id\":11}"},"message":"Rejected"}]"""

internal const val PrettyUnstableAlreadyMigratedArray: String =
    "[\n  {\n    \"type\": \"command_execution\",\n    \"call_id\": \"pending-complete\",\n    \"action\": {\n      \"type\": \"exec_command\",\n      \"arguments\": {\n        \"cmd\": \"pwd\",\n        \"tty\": false,\n        \"yield_time_ms\": 10000,\n        \"max_output_tokens\": 10000\n      }\n    }\n  }\n]"
