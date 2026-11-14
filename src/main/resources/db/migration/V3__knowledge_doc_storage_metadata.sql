-- File metadata is added incrementally so existing knowledge_doc rows remain valid.
ALTER TABLE knowledge_doc
    ADD COLUMN file_size BIGINT NULL AFTER content_hash,
    ADD COLUMN content_type VARCHAR(128) NULL AFTER file_size;
