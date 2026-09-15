-- =====================================================================
-- 账号池调度 SQL Patch：为 ai_account 表新增 worker 绑定相关字段
-- 
-- 执行方式（PowerShell）：
--   Get-Content "本文件路径" -Raw -Encoding UTF8 | docker exec -i geo-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 geo_backend
--
-- 新增字段说明：
--   worker_id    当前占用该账号的 worker（机器名），NULL 表示空闲可分配
--   batch_count  当前批次已处理的问题数（每处理完一个 +1，到 BATCH_SIZE 换号）
--   borrowed_at  账号被借走的时间戳（清扫器判断 worker 是否离线用）
--
-- 新增索引：
--   idx_ai_account_worker(worker_id)      让 selectIdleAccountForWorker 快速过滤空闲账号
--   idx_ai_account_platform_status_cooldown(platform, status, cooldown_until)
--     让 selectBestAccount / selectIdleAccountForWorker 的 WHERE 条件走索引
-- =====================================================================

-- 新增字段（用 ALTER TABLE ADD COLUMN，兼容已有表）
ALTER TABLE ai_account 
  ADD COLUMN worker_id VARCHAR(64) NULL COMMENT '当前占用该账号的worker标识（机器名），NULL表示空闲' AFTER priority,
  ADD COLUMN batch_count INT NULL DEFAULT 0 COMMENT '当前批次已处理问题数，达到BATCH_SIZE后worker应主动释放' AFTER worker_id,
  ADD COLUMN borrowed_at DATETIME NULL COMMENT '账号被借走的时间戳' AFTER batch_count;

-- 新增索引
ALTER TABLE ai_account 
  ADD INDEX idx_ai_account_worker (worker_id),
  ADD INDEX idx_ai_account_platform_status_cooldown (platform, status, cooldown_until);
