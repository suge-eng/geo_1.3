-- =============================================================
-- 数据库迁移补丁（任务池增强）
-- 用途：任务单元「卡住检测 + 通知」支撑
--   1. started_at     ：该单元进入 RUNNING(RUNNING) 的开始时刻（认领时写入，心跳不覆盖）
--   2. stuck_notified ：该单元是否已通知过「卡住」用户（避免重复发邮件）
-- 说明：若已执行过 schema_patch_taskpool.sql，本补丁只需再补下面两列即可。
-- =============================================================

ALTER TABLE task_result
    ADD COLUMN started_at DATETIME NULL COMMENT '认领开始执行时刻（用于卡住检测）' AFTER lease_expires_at,
    ADD COLUMN stuck_notified TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已通知卡住(0未通知 1已通知)' AFTER started_at;

CREATE INDEX idx_task_result_stuck
    ON task_result (status, started_at, stuck_notified);