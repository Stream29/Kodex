package io.github.stream29.kodex.app.migration.v0_4_7

/** Synthetic values only. Freeze with the 0.4.7 migration, independently of current models. */
internal val LegacySettingsFixture: String = """
    auth_source: kodex
    shell: /bin/bash
    new_line_key: enter
    context_sources:
      agents_home: false
      working_directory: false
      custom_sources:
        - path: ~/context
          enabled: false
    new_session:
      model: fixture-model
      reasoning_effort: ultra
      service_tier: fast
      request_user_input_mode: no_question
    session_title:
      enabled: false
      model: title-model
      reasoning_effort: custom-effort
    sidebars:
      left: none
      right: history_index
      left_width: 45
      right_width: 60
    hooks:
      discarded:
        type: stop
        command: echo should-never-run
    mcp_servers:
      http:
        type: streamable_http
        enabled: false
        url: https://fixture.invalid/mcp
        headers:
          Authorization: fixture-header
        oauth:
          type: initialized
          client:
            client_id: fixture-client
            client_secret: fixture-secret
            redirect_uri: http://127.0.0.1:8765/callback
          resource: fixture-resource
          scopes: [read, write]
          resolved_authorization_endpoint: https://fixture.invalid/authorize
          resolved_token_endpoint: https://fixture.invalid/token
          token_endpoint_auth_method: client_secret_basic
          access_token: fixture-access
          refresh_token: fixture-refresh
          token_type: Bearer
          expires_at_epoch_seconds: 9999999999
      process:
        type: stdio
        enabled: false
        command: fixture-command
        args: ["--flag", "value"]
        environment: {KEY: fixture-value}
        working_directory: relative/work
    unknown_future_field: keep-out-of-migration
""".trimIndent()
