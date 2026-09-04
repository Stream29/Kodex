package io.github.stream29.kodex.app.migration.v0_3_5

/**
 * Product-managed instructions installed into each Kodex Home by migration 0.3.5.
 *
 * Keep this value version-frozen. Content changes require a later Home migration.
 */
internal val KodexHomeSkill: String = """
    ---
    name: kodex-home
    description: Inspect the current Kodex Home, Sessions, settings, logs, credentials, and generated artifacts.
    ---

    # Kodex Home

    Use the current environment context to understand which Session you are running in.

    ## Current Session

    The `<current_session>` block identifies this Agent's own Session.

    - `storage_uri` is an opaque Kodex storage locator for the Session data directory.
    - A `file:///...` locator is a local path. Remove the `file://` prefix without
      percent-decoding it.
    - A Windows drive locator such as `file:///C:/Users/user/.kodex/sessions/42`
      maps to `C:/Users/user/.kodex/sessions/42`.
    - A UNC locator such as `file://server/share/sessions/42` maps to
      `//server/share/sessions/42`.
    - `memory:...` and unknown schemes do not identify readable filesystem history.
      Do not guess a path for them.
    - `name` is the current Session name. Use it to confirm that you are inspecting
      the intended Session.

    Do not infer a separate Session index from the locator. Resolved paths may have
    followed a symlink and need not end in a numeric directory.

    ## Home contents

    The default Kodex Home is `~/.kodex`. A custom Home is selected by the
    application and must not be guessed when it is not visible in the context.

    - `settings.yml`: sparse Kodex global settings, including Home, auth source,
      shell, input, new-Session, title, MCP, and Hook settings.
    - `auth.yml`: optional Kodex-owned credentials. Treat it as secret and never
      expose its contents.
    - `log/`: rolling Kodex application logs.
    - `generated_images/`: generated image artifacts.
    - `sessions/`: filesystem-backed root Sessions.
    - `skills/`: Home-local skills, including this product-managed skill.
    - `version.json`: Kodex Home migration version.

    Do not infer the Home root from the current Session locator. If the Home path is
    unknown, use only the paths explicitly available to you.

    ## Filesystem Session layout

    A filesystem Session data directory contains these six timelines:

    - `index/`: canonical stable history index.
    - `work/`: work and tool events.
    - `settings/`: Agent settings snapshots, including model, cwd, and thread name.
    - `timestamp/`: activity timestamps.
    - `token-count/`: token-count snapshots.
    - `unstable/`: pending-event snapshots. Never report unstable data as completed
      history.

    Each timeline stores change points as `<index>.json` and a `latest.json` pointer.
    The value visible at an index is the greatest stored change point not exceeding
    that index. Root Session directories may also contain `archive.mark` and
    `lock.json`.

    Use the latest visible `settings` value for Session metadata and the timestamp
    visible at an event index for activity. Raw stable history remains the source of
    truth; pending `unstable` data is not completed history.
""".trimIndent() + "\n"
