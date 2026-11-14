-- Reject unsafe legacy values BEFORE either persistent column is changed.
-- No cleanup, truncation or silent coercion of existing audit data is allowed.
CREATE TEMPORARY TABLE tdagent_v5_error_code_guard (
    invalid_value TINYINT NOT NULL,
    CONSTRAINT ck_tdagent_v5_no_invalid_error_code CHECK (invalid_value = 0)
);

INSERT INTO tdagent_v5_error_code_guard (invalid_value)
SELECT 1 FROM (
    SELECT error_code FROM agent_step WHERE error_code IS NOT NULL
    UNION ALL
    SELECT error_code FROM tool_call WHERE error_code IS NOT NULL
) AS legacy_error_codes
WHERE CASE
    WHEN error_code REGEXP '^(0|-?[1-9][0-9]*)$'
         AND error_code NOT REGEXP '[^0-9-]' AND CHAR_LENGTH(error_code) <= 11
    THEN CAST(error_code AS DECIMAL(65, 0)) NOT BETWEEN -2147483648 AND 2147483647
    ELSE TRUE
END
LIMIT 1;

DROP TEMPORARY TABLE tdagent_v5_error_code_guard;

ALTER TABLE agent_step MODIFY COLUMN error_code INT NULL;
ALTER TABLE tool_call MODIFY COLUMN error_code INT NULL;
