-- TDAgent V4: durable message idempotency, replay snapshots and Agent audit timing.
-- Java/Flyway is the only schema owner.
--
-- V1 already owns message.request_id, message.reply_to_message_id,
-- uk_message_request_id, uk_message_reply_to, agent_run.run_id,
-- agent_run.request_id and their unique indexes.  They are intentionally
-- not recreated here.

ALTER TABLE message
    ADD COLUMN update_time TIMESTAMP(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6)
        AFTER create_time,
    ADD COLUMN request_state VARCHAR(16) NULL AFTER request_id,
    ADD COLUMN request_error_http SMALLINT UNSIGNED NULL AFTER request_state,
    ADD COLUMN request_error_code INT NULL AFTER request_error_http,
    ADD COLUMN request_error_message VARCHAR(500) NULL AFTER request_error_code,
    ADD COLUMN answer_snapshot JSON NULL AFTER request_error_message,
    ADD COLUMN answer_snapshot_version SMALLINT UNSIGNED NULL AFTER answer_snapshot,
    ADD KEY idx_message_request_state_update_time (request_state, update_time);

ALTER TABLE agent_run
    ADD COLUMN started_at TIMESTAMP(6) NULL AFTER status,
    ADD COLUMN finished_at TIMESTAMP(6) NULL AFTER started_at,
    ADD COLUMN error_code INT NULL AFTER finished_at,
    ADD COLUMN error_message VARCHAR(500) NULL AFTER error_code,
    ADD KEY idx_agent_run_status_started_at (status, started_at);

-- Backfill the timing fields before making started_at mandatory for new runs.
UPDATE agent_run
SET started_at = COALESCE(create_time, CURRENT_TIMESTAMP(6)),
    finished_at = CASE
        WHEN status = 'RUNNING' THEN NULL
        ELSE COALESCE(update_time, create_time, CURRENT_TIMESTAMP(6))
    END
WHERE started_at IS NULL;

ALTER TABLE agent_run
    MODIFY COLUMN started_at TIMESTAMP(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6);

-- V1/stage-4 rows may already contain request_id but have no V4 state.  They
-- must never be sent to the model again.  The old stage-1 response shape is
-- reconstructed as a version-1 public snapshot with no fabricated sources or
-- usage.  UUID() is evaluated once per row and the persisted snapshot is the
-- sole replay source after migration.
UPDATE message AS user_message
JOIN message AS assistant_message
  ON assistant_message.reply_to_message_id = user_message.id
 AND assistant_message.role = 'assistant'
SET user_message.request_state = 'SUCCEEDED',
    user_message.answer_snapshot = JSON_OBJECT(
        'answer', assistant_message.content,
        'sources', JSON_ARRAY(),
        'taskType', 'knowledge_qa',
        'runId', UUID(),
        'traceId', UUID(),
        'usage', NULL,
        'executionTrace', JSON_OBJECT(
            'status', 'COMPLETED',
            'steps', JSON_ARRAY(),
            'toolCalls', JSON_ARRAY()
        ),
        'mock', TRUE
    ),
    user_message.answer_snapshot_version = 1,
    user_message.request_error_http = NULL,
    user_message.request_error_code = NULL,
    user_message.request_error_message = NULL
WHERE user_message.role = 'user'
  AND user_message.request_id IS NOT NULL
  AND user_message.request_state IS NULL;

-- A request row without its assistant response is an interrupted legacy
-- execution.  Mark it terminally failed rather than allowing a later retry
-- to invoke the model a second time.
UPDATE message AS user_message
LEFT JOIN message AS assistant_message
  ON assistant_message.reply_to_message_id = user_message.id
 AND assistant_message.role = 'assistant'
SET user_message.request_state = 'FAILED',
    user_message.request_error_http = 500,
    user_message.request_error_code = 5000,
    user_message.request_error_message = 'legacy request has no persisted assistant response',
    user_message.answer_snapshot = NULL,
    user_message.answer_snapshot_version = NULL
WHERE user_message.role = 'user'
  AND user_message.request_id IS NOT NULL
  AND user_message.request_state IS NULL
  AND assistant_message.id IS NULL;

-- request_id is a user-message idempotency key.  Any malformed historical
-- row carrying it on another role is made terminal and is never executable.
UPDATE message
SET request_state = 'FAILED',
    request_error_http = 500,
    request_error_code = 5000,
    request_error_message = 'invalid legacy request owner row',
    answer_snapshot = NULL,
    answer_snapshot_version = NULL
WHERE role <> 'user'
  AND request_id IS NOT NULL
  AND request_state IS NULL;
