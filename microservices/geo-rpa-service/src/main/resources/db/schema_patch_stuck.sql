-- =============================================================
-- 数据库迁移补丁（任务池增强）
-- 用途：任务单元「卡住检测 + 通知」支撑
--   1. started_at     ：该单元进入 RUNNING(RUNNING) 的开始时刻（认领时写入，心跳不覆盖）
--   2. stuck_notified ：该单元是否已通知过「卡住」用户（避免重复发邮件）
-- 说明：若已执行过 schema_patch_taskpool.sql，本补丁只需再补下面两列即可。
-- =============================================================

-- ---------------------------------------------------------------------
-- 【设计意图：卡住任务是如何被发现的】
--   started_at 在单元被认领、进入 RUNNING 的瞬间写入（心跳不会覆盖它），
--   于是“当前时间 - started_at”就是该单元已连续执行的时长。
--   后台检测器定时扫描：若 status=RUNNING 且执行时长超过阈值（配置 stuck-minutes），
--   即判定它“卡住”（worker 可能僵死），随后发邮件告警并把 stuck_notified 置 1，
--   保证同一单元只告警一次，避免邮件刷屏。
-- ---------------------------------------------------------------------

ALTER TABLE task_result
    ADD COLUMN started_at DATETIME NULL COMMENT '认领开始执行时刻（用于卡住检测）' AFTER lease_expires_at,
    ADD COLUMN stuck_notified TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已通知卡住(0未通知 1已通知)' AFTER started_at;

CREATE INDEX idx_task_result_stuck
    ON task_result (status, started_at, stuck_notified);