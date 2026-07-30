-- CRAWLER-013: 采集结果人工确认后入库。政策源级开关，存量政策源默认自动入库，不改变现有行为。
ALTER TABLE policy_source
  ADD COLUMN auto_index TINYINT NOT NULL DEFAULT 1 COMMENT 'Auto index crawled article into RAG: 1 auto, 0 manual confirm required'
  AFTER status;

-- index_status 增加 PENDING_CONFIRM 取值：等待人工确认后才入 RAG
ALTER TABLE policy_article
  MODIFY COLUMN index_status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
  COMMENT 'PENDING, PENDING_CONFIRM, INDEXING, SUCCESS, FAILED';

CREATE INDEX idx_policy_source_auto_index ON policy_source (project_id, auto_index, deleted);
