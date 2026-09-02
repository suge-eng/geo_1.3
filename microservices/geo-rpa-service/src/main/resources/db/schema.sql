-- =====================================================================
-- 完整建表脚本（geo_backend 库）
-- 依据 geo-common 实体类生成，已包含任务池字段：
--   task_result.assignee / lease_expires_at / started_at / stuck_notified
-- 执行方式（PowerShell）：
--   Get-Content "本文件路径" -Raw -Encoding UTF8 | docker exec -i geo-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 geo_backend
-- =====================================================================

-- ---------------------------------------------------------------------
-- 表 1：task —— 任务主表（一次提交 = 一个任务）
-- 设计意图：用户一次提交会带来「品牌 + 产品 + 若干问题 + 若干 AI」，
--           本表是这些信息的“总账”，记录任务整体状态、进度计数(已完成/失败)与最终报告。
-- 关系：1 个 task 对应多行 task_question、多行 task_ai、多行 task_result。
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- 表 2：task_question —— 任务拆分出的“问题”集合
-- 设计意图：把任务里的题目清单逐条落库，后续任务池按「问题 × AI」两两组合成执行单元。
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- 表 3：task_ai —— 任务关联的“AI 平台”集合
-- 设计意图：一个任务往往要同时去问多个 AI（如豆包、DeepSeek），这里记录每个 AI 的平台 code 及显示名。
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- 表 4：task_result —— 任务执行单元表，即“任务池”的核心
-- 设计意图（任务池机制）：
--   把一个任务拆成最小的「1 个问题 × 1 个 AI」单元，每行就是一个单元。
--   worker 脚本通过 HTTP 认领单元 → 心跳续租 → 完成后回调，全程围绕本表展开：
--     · assignee          ：该单元当前被哪个 worker 认领（机器名），为空表示可被认领
--     · lease_expires_at  ：租约截止时间，worker 心跳会把它不断往后续；
--                           若超时未续，清扫器判定 worker 掉线并把单元回收回流到池里
--     · started_at        ：进入 RUNNING 的时刻，与 stuck_notified 配合做“卡住检测”
--     · stuck_notified    ：是否已发过卡住告警，避免同一单元重复发邮件
--     · rpa_retry_count   ：回调失败后的重试计数
--   状态机：PENDING(待认领) → RUNNING(被认领执行中) → SUCCESS / FAILED / TIMEOUT
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- 表 5：task_report —— 报告表
-- 设计意图：分析服务把汇总计算出的品牌分析报告（JSON）落库，供任务/前端按 task_no 查询。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS task_report (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    task_no     VARCHAR(64)  NULL,
    report_json LONGTEXT     NULL COMMENT '报告JSON',
    report_date DATETIME     NULL COMMENT '报告日期',
    created_at  DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_task_report_task_no (task_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='报告表';

-- ---------------------------------------------------------------------
-- 表 6：ai_account —— AI 账号池表
-- 设计意图：worker 抓取需要以不同 AI 账号登录，本表集中管理账号及其健康度：
--     · cookie                登录态（worker 拿它去请求 AI）
--     · daily_limit/daily_used 每日限额与已用量（防止把账号额度刷爆）
--     · cooldown_until        冷却截止时间（被限流后暂停使用到该时刻）
--     · consecutive_failures / max_consecutive_failures 连续失败计数，达到上限即停用该账号
--     · priority              调度优先级（越大越优先被分配）
--   状态：ACTIVE(可用) / BANNED(封禁) / MAINTENANCE(维护) / EXHAUSTED(额度用尽)
-- ---------------------------------------------------------------------
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