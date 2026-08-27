PRAGMA journal_mode = WAL;
PRAGMA synchronous = FULL;
PRAGMA foreign_keys = ON;
PRAGMA user_version = 1;

CREATE TABLE agents (
    agent_id INTEGER PRIMARY KEY,
    parent_agent_id INTEGER REFERENCES agents(agent_id) ON DELETE CASCADE,
    entry_index INTEGER NOT NULL CHECK (entry_index >= 0),
    archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
    mtime_ns INTEGER NOT NULL,
    CHECK (parent_agent_id IS NULL OR archived = 0)
);

CREATE UNIQUE INDEX root_agent_entry_index
    ON agents(entry_index)
    WHERE parent_agent_id IS NULL;

CREATE UNIQUE INDEX child_agent_entry_index
    ON agents(parent_agent_id, entry_index)
    WHERE parent_agent_id IS NOT NULL;

CREATE TABLE compaction_entries (
    agent_id INTEGER NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
    state_index INTEGER NOT NULL CHECK (state_index >= 0),
    payload TEXT NOT NULL CHECK (json_valid(payload)),
    mtime_ns INTEGER NOT NULL,
    PRIMARY KEY (agent_id, state_index)
);

CREATE TABLE settings_entries (
    agent_id INTEGER NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
    state_index INTEGER NOT NULL CHECK (state_index >= 0),
    payload TEXT NOT NULL CHECK (json_valid(payload)),
    mtime_ns INTEGER NOT NULL,
    PRIMARY KEY (agent_id, state_index)
);

CREATE TABLE timestamp_entries (
    agent_id INTEGER NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
    state_index INTEGER NOT NULL CHECK (state_index >= 0),
    payload TEXT NOT NULL CHECK (json_valid(payload)),
    mtime_ns INTEGER NOT NULL,
    PRIMARY KEY (agent_id, state_index)
);

CREATE TABLE token_count_entries (
    agent_id INTEGER NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
    state_index INTEGER NOT NULL CHECK (state_index >= 0),
    payload TEXT NOT NULL CHECK (json_valid(payload)),
    mtime_ns INTEGER NOT NULL,
    PRIMARY KEY (agent_id, state_index)
);

CREATE TABLE stable_entries (
    agent_id INTEGER NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
    state_index INTEGER NOT NULL CHECK (state_index >= 0),
    payload TEXT NOT NULL CHECK (json_valid(payload)),
    mtime_ns INTEGER NOT NULL,
    PRIMARY KEY (agent_id, state_index)
);

CREATE TABLE unstable_entries (
    agent_id INTEGER NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
    state_index INTEGER NOT NULL CHECK (state_index >= 0),
    payload TEXT NOT NULL CHECK (json_valid(payload)),
    mtime_ns INTEGER NOT NULL,
    PRIMARY KEY (agent_id, state_index)
);

CREATE VIEW timeline_kinds(timeline_name) AS
    SELECT 'compaction'
    UNION ALL SELECT 'settings'
    UNION ALL SELECT 'timestamp'
    UNION ALL SELECT 'token-count'
    UNION ALL SELECT 'stable'
    UNION ALL SELECT 'unstable';

CREATE VIEW all_timeline_entries AS
    SELECT agent_id, 'compaction' AS timeline_name, state_index, payload, mtime_ns
    FROM compaction_entries
    UNION ALL
    SELECT agent_id, 'settings', state_index, payload, mtime_ns
    FROM settings_entries
    UNION ALL
    SELECT agent_id, 'timestamp', state_index, payload, mtime_ns
    FROM timestamp_entries
    UNION ALL
    SELECT agent_id, 'token-count', state_index, payload, mtime_ns
    FROM token_count_entries
    UNION ALL
    SELECT agent_id, 'stable', state_index, payload, mtime_ns
    FROM stable_entries
    UNION ALL
    SELECT agent_id, 'unstable', state_index, payload, mtime_ns
    FROM unstable_entries;

CREATE VIEW agent_paths AS
WITH RECURSIVE paths(
    agent_id,
    parent_agent_id,
    entry_index,
    path,
    parent_path,
    name,
    archived,
    mtime_ns
) AS (
    SELECT
        agent_id,
        parent_agent_id,
        entry_index,
        '/sessions/' || CAST(entry_index AS TEXT),
        '/sessions',
        CAST(entry_index AS TEXT),
        archived,
        mtime_ns
    FROM agents
    WHERE parent_agent_id IS NULL

    UNION ALL

    SELECT
        child.agent_id,
        child.parent_agent_id,
        child.entry_index,
        parent.path || '/subagents/' || CAST(child.entry_index AS TEXT),
        parent.path || '/subagents',
        CAST(child.entry_index AS TEXT),
        child.archived,
        child.mtime_ns
    FROM agents AS child
    JOIN paths AS parent
        ON child.parent_agent_id = parent.agent_id
)
SELECT
    agent_id,
    parent_agent_id,
    entry_index,
    path,
    parent_path,
    name,
    archived,
    mtime_ns
FROM paths;

CREATE VIEW timeline_heads AS
SELECT
    agent.agent_id,
    timeline.timeline_name,
    COALESCE(MAX(entry.state_index), -1) AS latest_index,
    COALESCE(MAX(entry.mtime_ns), agent.mtime_ns) AS mtime_ns
FROM agents AS agent
CROSS JOIN timeline_kinds AS timeline
LEFT JOIN all_timeline_entries AS entry
    ON entry.agent_id = agent.agent_id
    AND entry.timeline_name = timeline.timeline_name
GROUP BY agent.agent_id, timeline.timeline_name;

CREATE VIEW fuse_nodes AS
    SELECT
        '/' AS path,
        NULL AS parent_path,
        '' AS name,
        'directory' AS kind,
        NULL AS content,
        COALESCE((SELECT MAX(mtime_ns) FROM agents), 0) AS mtime_ns

    UNION ALL

    SELECT
        '/sessions',
        '/',
        'sessions',
        'directory',
        NULL,
        COALESCE((SELECT MAX(mtime_ns) FROM agents), 0)

    UNION ALL

    SELECT
        path.path,
        path.parent_path,
        path.name,
        'directory',
        NULL,
        path.mtime_ns
    FROM agent_paths AS path

    UNION ALL

    SELECT
        path.path || '/' || timeline.timeline_name,
        path.path,
        timeline.timeline_name,
        'directory',
        NULL,
        path.mtime_ns
    FROM agent_paths AS path
    CROSS JOIN timeline_kinds AS timeline

    UNION ALL

    SELECT
        path.path || '/subagents',
        path.path,
        'subagents',
        'directory',
        NULL,
        path.mtime_ns
    FROM agent_paths AS path

    UNION ALL

    SELECT
        path.path || '/' || entry.timeline_name || '/'
            || CAST(entry.state_index AS TEXT) || '.json',
        path.path || '/' || entry.timeline_name,
        CAST(entry.state_index AS TEXT) || '.json',
        'file',
        entry.payload,
        entry.mtime_ns
    FROM agent_paths AS path
    JOIN all_timeline_entries AS entry
        ON entry.agent_id = path.agent_id

    UNION ALL

    SELECT
        path.path || '/' || head.timeline_name || '/latest.json',
        path.path || '/' || head.timeline_name,
        'latest.json',
        'file',
        CAST(head.latest_index AS TEXT),
        head.mtime_ns
    FROM agent_paths AS path
    JOIN timeline_heads AS head
        ON head.agent_id = path.agent_id

    UNION ALL

    SELECT
        path.path || '/archive.mark',
        path.path,
        'archive.mark',
        'file',
        '',
        path.mtime_ns
    FROM agent_paths AS path
    WHERE path.parent_agent_id IS NULL
        AND path.archived = 1;

CREATE VIEW agent_summary AS
WITH latest_settings AS (
    SELECT entry.*
    FROM settings_entries AS entry
    JOIN (
        SELECT agent_id, MAX(state_index) AS state_index
        FROM settings_entries
        GROUP BY agent_id
    ) AS head
        ON head.agent_id = entry.agent_id
        AND head.state_index = entry.state_index
),
latest_timestamps AS (
    SELECT entry.*
    FROM timestamp_entries AS entry
    JOIN (
        SELECT agent_id, MAX(state_index) AS state_index
        FROM timestamp_entries
        GROUP BY agent_id
    ) AS head
        ON head.agent_id = entry.agent_id
        AND head.state_index = entry.state_index
),
latest_token_counts AS (
    SELECT entry.*
    FROM token_count_entries AS entry
    JOIN (
        SELECT agent_id, MAX(state_index) AS state_index
        FROM token_count_entries
        GROUP BY agent_id
    ) AS head
        ON head.agent_id = entry.agent_id
        AND head.state_index = entry.state_index
)
SELECT
    path.agent_id,
    path.parent_agent_id,
    path.entry_index,
    path.path,
    path.archived,
    json_extract(setting.payload, '$.threadName') AS thread_name,
    json_extract(setting.payload, '$.cwd') AS cwd,
    json_extract(setting.payload, '$.model') AS model,
    json_extract(timestamp.payload, '$') AS latest_timestamp,
    json_extract(token_count.payload, '$') AS latest_token_count,
    (SELECT MAX(state_index) FROM compaction_entries
        WHERE agent_id = path.agent_id) AS latest_compaction_index,
    (SELECT MAX(state_index) FROM settings_entries
        WHERE agent_id = path.agent_id) AS latest_settings_index,
    (SELECT MAX(state_index) FROM timestamp_entries
        WHERE agent_id = path.agent_id) AS latest_timestamp_index,
    (SELECT MAX(state_index) FROM token_count_entries
        WHERE agent_id = path.agent_id) AS latest_token_count_index,
    (SELECT MAX(state_index) FROM stable_entries
        WHERE agent_id = path.agent_id) AS latest_stable_index,
    (SELECT MAX(state_index) FROM unstable_entries
        WHERE agent_id = path.agent_id) AS latest_unstable_index
FROM agent_paths AS path
LEFT JOIN latest_settings AS setting
    ON setting.agent_id = path.agent_id
LEFT JOIN latest_timestamps AS timestamp
    ON timestamp.agent_id = path.agent_id
LEFT JOIN latest_token_counts AS token_count
    ON token_count.agent_id = path.agent_id;
