-- =====================================================================
-- 完整建表脚本（geo_backend 库）
-- 依据 geo-common 实体类生成，已包含任务池字段：
--   task_result.assignee / lease_expires_at / started_at / stuck_notified
-- 执行方式（PowerShell）：
--   Get-Content "本文件路径" -Raw -Encoding UTF8 | docker exec -i geo-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 geo_backend
-- =====================================================================

CREATE TABLE IF NOT EXISTS task (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    task_no              VARCHAR(64)  NULL COMMENT '任务号(唯一)',
    user_id              VARCHAR(64)  NULL COMMENT '用户标识',
    title                VARCHAR(500) NULL COMMENT '任务标题',
    status               VARCHAR(32)  NULL DEFAULT 'PENDING' COMMENT '任务状态 PENDING/PROCESSING/PAUSED/COMPLETED/PARTIAL_FAILED/FAILED/CANCELLED',
    total_count          INT          NULL DEFAULT 0 COMMENT '总单元数',
    completed_count      INT          NULL DEFAULT 0 COMMENT '已完成数',
    failed_count         INT          NULL DEFAULT 0 COMMENT '失败数',
    total_ai_count       INT          NULL DEFAULT 0 COMMENT 'AI数',
    total_question_count INT          NULL DEFAULT 0 COMMENT '问题数',
    brand_name           VARCHAR(255) NULL COMMENT '品牌名',
    product_name         VARCHAR(255) NULL COMMENT '产品名',
    competitors          TEXT         NULL COMMENT '竞品',
    report_json          LONGTEXT     NULL COMMENT '报告JSON',
    execution_frequency  VARCHAR(64)  NULL COMMENT '执行频率',
    next_run_time        DATETIME     NULL COMMENT '下次执行时间',
    error_msg            VARCHAR(1000) NULL COMMENT '错误信息',
    whitelist_urls       TEXT         NULL COMMENT '白名单URL',
    created_at           DATETIME     NULL,
    updated_at           DATETIME     NULL,
    completed_at         DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_no (task_no),
    KEY idx_task_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务主表';

CREATE TABLE IF NOT EXISTS task_question (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    task_id       BIGINT       NULL,
    task_no       VARCHAR(64)  NULL,
    question_text TEXT         NULL COMMENT '问题内容',
    sort_order    INT          NULL DEFAULT 0 COMMENT '排序',
    created_at    DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_task_question_task_no (task_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务问题表';

CREATE TABLE IF NOT EXISTS task_ai (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    task_id        BIGINT       NULL,
    task_no        VARCHAR(64)  NULL,
    ai_platform    VARCHAR(32)  NULL COMMENT '平台code',
    ai_display_name VARCHAR(128) NULL COMMENT '平台显示名',
    created_at     DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_task_ai_task_no (task_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务-AI关联表';

CREATE TABLE IF NOT EXISTS task_result (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    task_id          BIGINT       NULL,
    task_no          VARCHAR(64)  NULL,
    task_question_id BIGINT       NULL,
    ai_platform      VARCHAR(32)  NULL COMMENT '平台code',
    question_text    TEXT         NULL COMMENT '问题内容',
    answer_text      LONGTEXT     NULL COMMENT '回答内容',
    thinking_content LONGTEXT     NULL COMMENT '思考过程',
    source_info      TEXT         NULL COMMENT '来源信息',
    screenshot_urls  TEXT         NULL COMMENT '截图URL(逗号分隔)',
    status           VARCHAR(32)  NULL DEFAULT 'PENDING' COMMENT '单元状态 PENDING/RUNNING/SUCCESS/FAILED/TIMEOUT',
    error_msg        VARCHAR(1000) NULL COMMENT '错误信息',
    duration_ms      BIGINT       NULL COMMENT '耗时毫秒',
    rpa_retry_count  INT          NULL DEFAULT 0 COMMENT 'RPA重试次数',
    account_id       BIGINT       NULL COMMENT '使用的AI账号id',
    assignee         VARCHAR(64)  NULL COMMENT '认领该单元的worker标识(机器名)',
    lease_expires_at DATETIME     NULL COMMENT '租约截止时间',
    started_at       DATETIME     NULL COMMENT '开始执行时刻(卡住检测用)',
    stuck_notified   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已通知卡住(0未通知 1已通知)',
    created_at       DATETIME     NULL,
    completed_at     DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_task_result_task_no (task_no),
    KEY idx_task_result_platform_status_id (ai_platform, status, id),
    KEY idx_task_result_status_lease (status, lease_expires_at),
    KEY idx_task_result_stuck (status, started_at, stuck_notified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务执行单元表(任务池)';

CREATE TABLE IF NOT EXISTS task_report (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    task_no     VARCHAR(64)  NULL,
    report_json LONGTEXT     NULL COMMENT '报告JSON',
    report_date DATETIME     NULL COMMENT '报告日期',
    created_at  DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_task_report_task_no (task_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='报告表';

CREATE TABLE IF NOT EXISTS ai_account (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    platform                VARCHAR(32)  NULL COMMENT '平台code',
    account_name            VARCHAR(128) NULL COMMENT '账号名',
    cookie                  LONGTEXT     NULL COMMENT '登录cookie',
    status                  VARCHAR(32)  NULL DEFAULT 'ACTIVE' COMMENT '账号状态 ACTIVE/BANNED/MAINTENANCE/EXHAUSTED',
    daily_limit             INT          NULL DEFAULT 0 COMMENT '每日限额',
    daily_used              INT          NULL DEFAULT 0 COMMENT '今日已用',
    daily_reset_at          DATETIME     NULL COMMENT '每日重置时间',
    last_request_at         DATETIME     NULL COMMENT '最近请求时间',
    cooldown_until          DATETIME     NULL COMMENT '冷却截止时间',
    request_interval_ms     INT          NULL DEFAULT 0 COMMENT '请求间隔(毫秒)',
    consecutive_failures    INT          NULL DEFAULT 0 COMMENT '连续失败次数',
    max_consecutive_failures INT         NULL DEFAULT 0 COMMENT '最大连续失败次数',
    priority                INT          NULL DEFAULT 0 COMMENT '优先级',
    created_at              DATETIME     NULL,
    updated_at              DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_ai_account_platform (platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI账号池表';