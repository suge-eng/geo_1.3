-- =====================================================================
-- 任务池 + 分发器 数据库迁移脚本（在已存在的 geo_backend 库上执行一次即可）
-- 用途：为 task_result 增加「认领者 / 租约截止时间」两列，并建立认领查询索引。
--   assignee        该单元当前被哪个 worker 认领（脚本所在机器名）
--   lease_expires_at  租约截止时间，worker 心跳续约，超时未续会被清扫器回收回流
-- 执行方式：mysql -h127.0.0.1 -P3307 -ugeo -pgeo123 geo_backend < 本文件
-- =====================================================================

ALTER TABLE task_result
    ADD COLUMN assignee VARCHAR(64) NULL COMMENT '认领该单元的worker标识(机器名)' AFTER rpa_retry_count,
    ADD COLUMN lease_expires_at DATETIME NULL COMMENT '租约截止时间' AFTER assignee;

-- 认领查询：按 (平台, 状态, id) 走索引，FIFO
CREATE INDEX idx_task_result_platform_status_id
    ON task_result (ai_platform, status, id);

-- 租约清扫：按状态+租约时间走索引
CREATE INDEX idx_task_result_status_lease
    ON task_result (status, lease_expires_at);