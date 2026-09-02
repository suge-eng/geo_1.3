package com.geo.service;

import com.geo.common.BusinessException;
import com.geo.common.ResultCode;
import com.geo.common.SentimentUtils;
import com.geo.dto.TaskProgressVO;
import com.geo.dto.TaskRankingVO;
import com.geo.dto.TaskResultVO;
import com.geo.entity.Task;
import com.geo.entity.TaskAi;
import com.geo.entity.TaskQuestion;
import com.geo.entity.TaskResult;
import com.geo.enums.AiPlatform;
import com.geo.enums.ResultStatus;
import com.geo.enums.TaskStatus;
import com.geo.mapper.TaskAiMapper;
import com.geo.mapper.TaskMapper;
import com.geo.mapper.TaskQuestionMapper;
import com.geo.mapper.TaskResultMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 【任务核心服务 - 系统大脑】
 * 设计思路：
 * 1. 这是整个任务系统的"总指挥"，管理任务的完整生命周期：创建→提交→调度→执行→结果→重试→删除。
 * 2. 分层设计：
 *    - 顶层接口：面向Controller的公开方法（createTask、submitTask、deleteTask等）
 *    - 业务编排：将数据库操作、调度、RPA调用组合成完整业务流程
 *    - 私有辅助：验证、编号生成、数据转换等内部方法
 * 3. 数据一致性：所有写操作使用@Transactional，确保多张表更新要么全成功要么全失败。
 * 4. 状态机驱动：严格通过TaskStatus和ResultStatus枚举控制状态流转，防止非法操作。
 * 5. 结果同步双模式：
 *    - 实时模式：每次RPA回调后立即调用updateTaskProgress
 *    - 惰性模式：查询时调用syncTaskProgressFromResults兜底刷新（防止回调丢失）
 * 6. 排名缓存策略：任务终态后缓存排名结果，避免重复计算（排名计算逻辑较重）。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /**
     * 【排名结果缓存】
     * 设计思路：
     * - 使用ConcurrentHashMap实现线程安全的本地缓存
     * - Key: taskNo，Value: 计算好的排名VO
     * - 缓存时机：任务进入终态（COMPLETED/FAILED/PARTIAL_FAILED/CANCELLED）后缓存
     * - 优点：排名计算涉及大量字符串匹配和统计，终态后结果不再变化，缓存可大幅提升性能
     * - 局限：单实例本地缓存，集群部署时各实例各算各的（可接受，因为最终结果一致）
     */
    private final ConcurrentHashMap<String, TaskRankingVO> rankingCache = new ConcurrentHashMap<>();

    private final TaskMapper taskMapper;
    private final TaskAiMapper taskAiMapper;
    private final TaskQuestionMapper taskQuestionMapper;
    private final TaskResultMapper taskResultMapper;
    private final ObjectMapper objectMapper;
    private final TaskScheduleService taskScheduleService;
    private final RestTemplate restTemplate;

    /**
     * RPA服务调度接口地址
     * 设计思路：通过@Value注入配置，支持通过配置中心动态修改，默认值方便本地开发
     */
    @Value("${geo.rpa.url:http://localhost:8084/internal/rpa/dispatch}")
    private String rpaDispatchUrl;

    /**
     * 【构造函数注入 - 依赖注入最佳实践】
     * 设计思路：
     * - 使用构造函数注入而非@Autowired字段注入：
     *   1. 依赖不可变（final字段），运行时不会被意外修改
     *   2. 便于单元测试（可以手动mock依赖传入）
     *   3. 依赖缺失时启动即报错，避免运行时空指针
     * - @Lazy TaskScheduleService：解决循环依赖问题
     *   TaskService需要调度服务注册任务，TaskScheduleService也可能回调TaskService触发执行
     */
    public TaskService(TaskMapper taskMapper, TaskAiMapper taskAiMapper,
                       TaskQuestionMapper taskQuestionMapper, TaskResultMapper taskResultMapper,
                       ObjectMapper objectMapper,
                       @Lazy TaskScheduleService taskScheduleService,
                       RestTemplate restTemplate) {
        this.taskMapper = taskMapper;
        this.taskAiMapper = taskAiMapper;
        this.taskQuestionMapper = taskQuestionMapper;
        this.taskResultMapper = taskResultMapper;
        this.objectMapper = objectMapper;
        this.taskScheduleService = taskScheduleService;
        this.restTemplate = restTemplate;
    }

    /**
     * 【RPA任务分发 - 微服务间通信】
     * 设计思路：
     * 1. 职责单一：只负责将任务信息通过HTTP POST发送给RPA服务，不关心RPA内部如何执行
     * 2. 容错设计：
     *    - 调用失败只打日志不抛出异常，避免影响主业务流程（任务已经创建成功了）
     *    - RPA服务有补偿机制（扫描PENDING状态的任务），即使这次调用失败也会被兜底处理
     * 3. 使用LinkedHashMap：保持body字段顺序与业务逻辑一致，便于调试日志阅读
     *
     * @param taskNo            任务编号
     * @param results           待执行的小任务列表（1问题x1平台=1TaskResult）
     * @param brandName         自主品牌名
     * @param productName       产品名
     * @param competitors       竞争对手列表
     * @param executionFrequency 执行频率（single/daily/weekly）
     * @param retryOnFailure    失败是否重试
     */
    private void dispatchToRpa(String taskNo, List<TaskResult> results, String brandName,
                               String productName, List<String> competitors,
                               String executionFrequency, Boolean retryOnFailure) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("taskNo", taskNo);
            body.put("results", results);
            body.put("brandName", brandName);
            body.put("productName", productName);
            body.put("competitors", competitors);
            body.put("executionFrequency", executionFrequency);
            body.put("retryOnFailure", retryOnFailure);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    rpaDispatchUrl, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("调用RPA调度接口失败: taskNo={}, status={}, body={}",
                        taskNo, response.getStatusCode(), response.getBody());
            } else {
                log.debug("调用RPA调度接口成功: taskNo={}, resultCount={}", taskNo, results.size());
            }
        } catch (Exception e) {
            log.error("调用RPA调度接口异常: taskNo={}", taskNo, e);
        }
    }

    /**
     * 【创建任务 - 重载简化版】
     * 设计思路：提供不含白名单URL的简化版本，兼容旧调用方
     */
    @Transactional
    public Task createTask(List<String> aiPlatforms, List<String> questions, String title,
                          String brandName, String productName, List<String> competitors,
                          String executionFrequency, Boolean retryOnFailure, String scope) {
        return createTask(aiPlatforms, questions, title, brandName, productName, competitors,
                executionFrequency, retryOnFailure, scope, null);
    }

    /**
     * 【创建任务 - 核心方法】
     * 设计思路：
     * 1. 任务创建是"1拆N"的过程：
     *    - 1个Task（主任务）
     *    - N个TaskAi（选用的AI平台记录）
     *    - M个TaskQuestion（问题列表）
     *    - N*M个TaskResult（每个问题在每个平台上的执行单元 = 最小调度单位）
     * 2. 数据冗余设计：
     *    - TaskResult冗余存储questionText和aiPlatform：虽然可以join查到，但查询时避免多表关联
     *    - Task冗余存储totalCount/completedCount：前端进度查询不用每次count子表
     * 3. JSON字段存储：
     *    - competitors、whitelistUrls用JSON字符串存：因为长度不固定，用JSON比建关联表更灵活
     *    - 缺点是无法用SQL索引查询，优点是开发效率高、schema变更成本低
     * 4. @Transactional：任何一步失败全部回滚，保证数据完整性
     *
     * @param aiPlatforms       选用的AI平台编码列表
     * @param questions         问题文本列表
     * @param title             任务标题
     * @param brandName         自主品牌名（必填）
     * @param productName       产品名
     * @param competitors       竞争对手品牌列表
     * @param executionFrequency 执行频率
     * @param retryOnFailure    失败是否自动重试
     * @param scope             地域范围（LOCAL/GLOBAL）
     * @param whitelistUrls     白名单URL列表
     * @return 创建好的Task实体
     */
    @Transactional
    public Task createTask(List<String> aiPlatforms, List<String> questions, String title,
                          String brandName, String productName, List<String> competitors,
                          String executionFrequency, Boolean retryOnFailure, String scope,
                          List<String> whitelistUrls) {
        validateAiPlatforms(aiPlatforms);
        validateQuestions(questions);
        validateBrandName(brandName);

        String taskNo = generateTaskNo();
        Task task = new Task();
        task.setTaskNo(taskNo);
        task.setUserId("anonymous");
        task.setTitle(title);
        task.setStatus(TaskStatus.PENDING.name());
        task.setTotalAiCount(aiPlatforms.size());
        task.setTotalQuestionCount(questions.size());
        task.setTotalCount(aiPlatforms.size() * questions.size());
        task.setCompletedCount(0);
        task.setFailedCount(0);
        task.setBrandName(brandName);
        task.setProductName(productName);
        task.setExecutionFrequency(executionFrequency != null ? executionFrequency : "single");
        task.setScope(scope != null ? scope : "LOCAL");
        task.setQuestionCount(questions.size());
        task.setIntentCount(0);
        try {
            task.setCompetitors(objectMapper.writeValueAsString(competitors));
        } catch (JsonProcessingException e) {
            log.warn("序列化竞争对手列表失败", e);
        }
        if (whitelistUrls != null && !whitelistUrls.isEmpty()) {
            try {
                task.setWhitelistUrls(objectMapper.writeValueAsString(whitelistUrls));
            } catch (JsonProcessingException e) {
                log.warn("序列化白名单URLs失败", e);
            }
        }

        taskMapper.insert(task);

        saveTaskAis(task.getId(), taskNo, aiPlatforms);
        List<TaskQuestion> savedQuestions = saveTaskQuestions(task.getId(), taskNo, questions);
        saveTaskCompetitors(task.getId(), taskNo, competitors);

        List<TaskResult> taskResults = createTaskResults(task.getId(), taskNo, aiPlatforms, savedQuestions, brandName);
        for (TaskResult result : taskResults) {
            taskResultMapper.insert(result);
        }

        return task;
    }

    /**
     * 【提交任务 - 从草稿到运行】
     * 设计思路：
     * 1. 状态校验：只允许PENDING状态提交，防止重复提交或对已运行任务操作
     * 2. 三步走：
     *    a. 更新Task状态为PROCESSING，计算下次执行时间
     *    b. 向TaskScheduleService注册调度（如果是周期任务）
     *    c. 调用RPA服务分发待执行的小任务
     * 3. 设计上create和submit分离的原因：
     *    - 允许用户创建后预览、修改、再提交
     *    - 便于Excel批量导入场景：先全部创建好，再统一提交
     *
     * @param taskNo             任务编号
     * @param executionFrequency 执行频率（可覆盖创建时的值）
     * @param retryOnFailure     失败是否重试
     * @return 更新后的Task
     */
    @Transactional
    public Task submitTask(String taskNo, String executionFrequency, Boolean retryOnFailure) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        if (!TaskStatus.PENDING.name().equals(task.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "任务状态不允许提交");
        }

        task.setStatus(TaskStatus.PROCESSING.name());
        task.setExecutionFrequency(executionFrequency != null ? executionFrequency : task.getExecutionFrequency());
        if (task.getExecutionFrequency() == null || task.getExecutionFrequency().isEmpty()) {
            task.setExecutionFrequency("single");
        }
        task.setNextRunTime(calculateNextRunTime(task.getExecutionFrequency()));
        taskMapper.updateById(task);
        taskScheduleService.scheduleTask(task);

        List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);

        List<String> competitorsList = new ArrayList<>();
        if (task.getCompetitors() != null && !task.getCompetitors().isEmpty()) {
            try {
                competitorsList = objectMapper.readValue(task.getCompetitors(), new TypeReference<List<String>>() {});
            } catch (JsonProcessingException e) {
                log.warn("解析竞争对手列表失败", e);
            }
        }

        dispatchToRpa(taskNo, results, task.getBrandName(), task.getProductName(), competitorsList,
                executionFrequency, retryOnFailure);

        log.info("提交任务: taskNo={}", taskNo);
        return task;
    }

    /**
     * 【删除任务 - 级联清理】
     * 设计思路：
     * 1. 运行中禁止删除：防止RPA正在写数据时主表被删导致脏数据
     * 2. 取消调度：如果是周期任务，先从调度器移除，防止后续还被触发
     * 3. 级联删除顺序：先删子表（TaskResult/Question/Ai），后删主表（Task）
     *    这是数据库外键约束的要求（虽然项目可能没建物理外键，但逻辑上遵守）
     */
    @Transactional
    public void deleteTask(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        String status = task.getStatus();
        if (TaskStatus.PROCESSING.name().equals(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "任务正在运行中，无法删除");
        }

        taskScheduleService.cancelTask(taskNo);

        taskResultMapper.deleteByTaskNo(taskNo);
        taskQuestionMapper.deleteByTaskNo(taskNo);
        taskAiMapper.deleteByTaskNo(taskNo);
        taskMapper.deleteById(task.getId());

        log.info("删除任务: taskNo={}", taskNo);
    }

    /**
     * 【精确调度触发周期任务】
     * 设计思路：
     * 1. 这是"内存调度器"触发的入口，由TaskScheduleService在预定时间点调用
     * 2. 与rescheduleDueTasks（DB兜底扫描）的区别：
     *    - 本方法：精确到秒级触发，靠内存中的ScheduledFuture
     *    - rescheduleDueTasks：定时扫描DB，兜底补偿漏掉的任务
     * 3. 执行前做三重校验：任务存在、已到时间、非运行中
     * 4. 重置所有TaskResult状态：周期任务每次都是全新一轮，把上次结果清空
     * 5. rpaRetryCount累加：记录这个小任务被周期重跑了多少次
     */
    public void rescheduleDueSingleTask(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) return;
        if (!isTaskDue(task)) return;
        if (TaskStatus.PROCESSING.name().equals(task.getStatus())) return;

        log.info("[精确调度] 触发周期任务: taskNo={}, nextRunTime={}, executionFrequency={}",
                taskNo, task.getNextRunTime(), task.getExecutionFrequency());
        try {
            task.setStatus(TaskStatus.PROCESSING.name());
            task.setCompletedAt(null);
            task.setNextRunTime(calculateNextRunTime(task.getExecutionFrequency()));
            taskMapper.updateById(task);
            taskScheduleService.scheduleTask(task);

            List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);
            for (TaskResult result : results) {
                result.setStatus(ResultStatus.PENDING.name());
                result.setErrorMsg(null);
                result.setAnswerText(null);
                result.setScreenshotUrls(null);
                result.setDurationMs(null);
                result.setCompletedAt(null);
                result.setCreatedAt(LocalDateTime.now());
                result.setRpaRetryCount(result.getRpaRetryCount() != null ? result.getRpaRetryCount() + 1 : 0);
                taskResultMapper.updateById(result);
            }

            List<String> competitorsList = new ArrayList<>();
            if (task.getCompetitors() != null && !task.getCompetitors().isEmpty()) {
                try {
                    competitorsList = objectMapper.readValue(task.getCompetitors(), new TypeReference<List<String>>() {});
                } catch (JsonProcessingException e) {
                    log.warn("解析竞争对手列表失败", e);
                }
            }
            dispatchToRpa(taskNo, results, task.getBrandName(), task.getProductName(), competitorsList, "single", true);
        } catch (Exception e) {
            log.error("[精确调度] 触发周期任务失败: taskNo={}", taskNo, e);
        }
    }

    /**
     * 【验证品牌名】
     * 设计思路：参数校验放在Service层而非Controller层，原因：
     * - 复用性：多个Controller或内部调用都能走同一份校验逻辑
     * - 事务内：如果校验和DB操作在一个事务里，能保证一致性
     */
    private void validateBrandName(String brandName) {
        if (brandName == null || brandName.trim().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "自主品牌名不能为空");
        }
        if (brandName.length() > 100) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "自主品牌名长度不能超过100字符");
        }
    }

    /**
     * 【验证AI平台列表】
     * 设计思路：遍历每个平台编码，用AiPlatform枚举的fromCode反查，确保只传系统支持的平台
     */
    private void validateAiPlatforms(List<String> aiPlatforms) {
        if (aiPlatforms == null || aiPlatforms.isEmpty()) {
            throw new BusinessException(ResultCode.AI_LIST_EMPTY);
        }
        for (String platform : aiPlatforms) {
            if (AiPlatform.fromCode(platform) == null) {
                throw new BusinessException(ResultCode.AI_PLATFORM_INVALID, "不支持的AI平台: " + platform);
            }
        }
    }

    /**
     * 【验证问题列表】
     * 设计思路：限制1000个问题是为了防止用户误操作提交巨量任务拖垮系统
     */
    private void validateQuestions(List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new BusinessException(ResultCode.QUESTION_EMPTY);
        }
        if (questions.size() > 1000) {
            throw new BusinessException(ResultCode.QUESTION_TOO_MANY);
        }
        for (String question : questions) {
            if (question == null || question.trim().isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "问题不能为空");
            }
            if (question.length() > 1000) {
                throw new BusinessException(ResultCode.QUESTION_TOO_LONG);
            }
        }
    }

    /**
     * 【生成任务编号】
     * 设计思路：
     * - 格式：T + yyyyMMddHHmmss + 8位UUID大写
     * - 可读性：时间戳让人一眼知道任务创建时间，方便排查
     * - 唯一性：秒级时间戳+UUID基本不可能重复（UUID前8位碰撞概率极低）
     * - 前缀T：和其他业务表编号区分（如未来的R开头报告、A开头账号）
     */
    private String generateTaskNo() {
        String timestamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        return "T" + timestamp + uuid.toUpperCase();
    }

    /**
     * 【保存任务-AI平台关联】
     * 设计思路：冗余存displayName，避免查询时还要去枚举转换
     */
    private void saveTaskAis(Long taskId, String taskNo, List<String> aiPlatforms) {
        for (String platform : aiPlatforms) {
            AiPlatform aiPlatform = AiPlatform.fromCode(platform);
            TaskAi taskAi = new TaskAi();
            taskAi.setTaskId(taskId);
            taskAi.setTaskNo(taskNo);
            taskAi.setAiPlatform(platform);
            taskAi.setAiDisplayName(aiPlatform != null ? aiPlatform.getDisplayName() : platform);
            taskAiMapper.insert(taskAi);
        }
    }

    /**
     * 【保存问题列表】
     * 设计思路：
     * - sortOrder记录用户原始顺序，前端展示和排名计算都要按这个顺序
     * - 返回保存后的实体（带自增ID），供后面createTaskResults关联使用
     */
    private List<TaskQuestion> saveTaskQuestions(Long taskId, String taskNo, List<String> questions) {
        List<TaskQuestion> taskQuestions = new ArrayList<>();
        int order = 0;
        for (String question : questions) {
            TaskQuestion tq = new TaskQuestion();
            tq.setTaskId(taskId);
            tq.setTaskNo(taskNo);
            tq.setQuestionText(question);
            tq.setSortOrder(order++);
            taskQuestionMapper.insert(tq);
            taskQuestions.add(tq);
        }
        return taskQuestions;
    }

    /**
     * 【保存竞争对手为特殊问题】
     * 设计思路：
     * - 竞争对手也被当作一种"特殊问题"存入TaskQuestion表
     * - sortOrder=999：确保排在用户正常问题之后
     * - 问题文本统一前缀"竞争对手: "，便于后续识别和过滤
     * - 这样做的好处：复用TaskResult机制，竞争对手查询也走同一套流程
     */
    private void saveTaskCompetitors(Long taskId, String taskNo, List<String> competitors) {
        if (competitors == null || competitors.isEmpty()) {
            return;
        }
        for (String competitor : competitors) {
            if (competitor != null && !competitor.trim().isEmpty()) {
                TaskQuestion tq = new TaskQuestion();
                tq.setTaskId(taskId);
                tq.setTaskNo(taskNo);
                tq.setQuestionText("竞争对手: " + competitor.trim());
                tq.setSortOrder(999);
                taskQuestionMapper.insert(tq);
            }
        }
    }

    /**
     * 【生成最小执行单元TaskResult】
     * 设计思路：
     * - 笛卡尔积：每个问题 × 每个平台 = 一个TaskResult
     * - 这是整个系统调度的"原子单位"，RPA Worker每次领取的就是这个
     * - 冗余存questionText和aiPlatform：后续查询不用join，RPA拿到也直接能用
     */
    private List<TaskResult> createTaskResults(Long taskId, String taskNo,
                                                List<String> aiPlatforms,
                                                List<TaskQuestion> questions,
                                                String brandName) {
        List<TaskResult> results = new ArrayList<>();
        for (TaskQuestion question : questions) {
            for (String platform : aiPlatforms) {
                TaskResult result = new TaskResult();
                result.setTaskId(taskId);
                result.setTaskNo(taskNo);
                result.setTaskQuestionId(question.getId());
                result.setAiPlatform(platform);
                result.setQuestionText(question.getQuestionText());
                result.setStatus(ResultStatus.PENDING.name());
                result.setRpaRetryCount(0);
                results.add(result);
            }
        }
        return results;
    }

    /**
     * 【按任务编号查询】
     * 设计思路：封装MyBatis-Plus的QueryWrapper，避免Controller层重复写条件构造
     */
    public Task getTaskByNo(String taskNo) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.eq("task_no", taskNo);
        return taskMapper.selectOne(wrapper);
    }

    /**
     * 【查询任务并刷新进度 - 带事务】
     * 设计思路：
     * - 对外暴露的"可靠查询"方法，查完立即同步一次进度
     * - 解决场景：RPA回调失败/网络丢包时，用户主动点详情可以触发兜底刷新
     * - syncTaskProgressFromResults内部会updateById，所以需要事务
     */
    @org.springframework.transaction.annotation.Transactional
    public Task getTaskByNoWithProgressRefresh(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) return null;
        try {
            syncTaskProgressFromResults(task);
        } catch (Exception e) {
            log.debug("刷新任务进度失败: taskNo={}", taskNo, e);
        }
        return task;
    }

    /**
     * 【任务列表查询】
     * 设计思路：
     * - 按创建时间倒序，最新的在最前面
     * - 兼容老数据回填：scope、questionCount、intentCount这些是后来加的字段，老数据可能为null
     *   这里不update DB，只在返回前给默认值，避免大量老数据迁移
     */
    public List<Task> listTasks() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.orderByDesc("created_at");
        List<Task> tasks = taskMapper.selectList(wrapper);
        for (Task task : tasks) {
            if (task.getScope() == null || task.getScope().isEmpty()) {
                task.setScope("LOCAL");
            }
            if (task.getQuestionCount() == null) {
                task.setQuestionCount(task.getTotalQuestionCount() != null ? task.getTotalQuestionCount() : 0);
            }
            if (task.getIntentCount() == null) {
                task.setIntentCount(0);
            }
        }
        return tasks;
    }

    /**
     * 【任务列表查询（带进度刷新）】
     * 设计思路：
     * - 列表页展示的进度可能有延迟，所以PENDING/PROCESSING状态的任务强制刷新
     * - 终态任务做"惰性校验"：如果缓存的总数和实际数对不上，说明有过删改操作，也触发刷新
     * - 这是性能与一致性的平衡：终态任务大多数情况不用刷，少数异常情况才刷
     */
    @org.springframework.transaction.annotation.Transactional
    public List<Task> listTasksWithProgressRefresh() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.orderByDesc("created_at");
        List<Task> tasks = taskMapper.selectList(wrapper);
        for (Task task : tasks) {
            String status = task.getStatus();
            boolean needSync = TaskStatus.PENDING.name().equals(status)
                    || TaskStatus.PROCESSING.name().equals(status);
            if (!needSync) {
                Integer cachedTotal = task.getTotalCount();
                Integer cachedCompleted = task.getCompletedCount();
                List<TaskResult> results = taskResultMapper.selectByTaskId(task.getId());
                int actualTotal = results.size();
                long actualCompleted = results.stream().filter(r ->
                        ResultStatus.SUCCESS.name().equals(r.getStatus())).count();
                if (cachedTotal == null || cachedTotal != actualTotal
                        || cachedCompleted == null || cachedCompleted.intValue() != actualCompleted) {
                    needSync = true;
                }
            }
            if (needSync) {
                try {
                    syncTaskProgressFromResults(task);
                } catch (Exception e) {
                    log.debug("刷新任务进度失败: taskNo={}", task.getTaskNo(), e);
                }
            }
            if (task.getScope() == null || task.getScope().isEmpty()) {
                task.setScope("LOCAL");
            }
            if (task.getQuestionCount() == null) {
                task.setQuestionCount(task.getTotalQuestionCount() != null ? task.getTotalQuestionCount() : 0);
            }
            if (task.getIntentCount() == null) {
                task.setIntentCount(0);
            }
        }
        return tasks;
    }

    /**
     * 【从TaskResult子表同步进度到Task主表】
     * 设计思路：
     * 1. 进度同步的核心算法：遍历所有子任务，按状态归类计数
     * 2. 状态迁移逻辑（所有子任务处理完才变主任务状态）：
     *    - 全成功 → COMPLETED
     *    - 部分成功部分失败 → PARTIAL_FAILED
     *    - 全失败 → FAILED
     * 3. 任务完成后调用taskScheduleService.scheduleTask：
     *    - 对于周期任务，scheduleTask内部会注册下次触发时间
     *    - 对于单次任务，scheduleTask内部会忽略
     * 4. 这个方法和updateTaskProgress逻辑几乎一样，区别是：
     *    - syncTaskProgressFromResults：查询时兜底调用
     *    - updateTaskProgress：RPA回调后实时调用
     *    （代码重复是故意的，两者在处理完成后调度的细节有细微差别，也可后续重构抽取）
     */
    private void syncTaskProgressFromResults(Task task) {
        if (task == null || task.getId() == null) return;
        List<TaskResult> results = taskResultMapper.selectByTaskId(task.getId());
        long successCount = 0;
        long failedCount = 0;
        long pendingCount = 0;
        for (TaskResult r : results) {
            String s = r.getStatus();
            if (ResultStatus.SUCCESS.name().equals(s)) {
                successCount++;
            } else if (ResultStatus.FAILED.name().equals(s) || ResultStatus.TIMEOUT.name().equals(s)) {
                failedCount++;
            } else {
                pendingCount++;
            }
        }
        task.setTotalCount(results.size());
        task.setCompletedCount((int) successCount);
        task.setFailedCount((int) failedCount);

        boolean statusChanged = false;
        if (pendingCount == 0) {
            String newStatus;
            if (failedCount == 0) {
                newStatus = TaskStatus.COMPLETED.name();
            } else if (successCount > 0) {
                newStatus = TaskStatus.PARTIAL_FAILED.name();
            } else {
                newStatus = TaskStatus.FAILED.name();
            }
            if (!newStatus.equals(task.getStatus())) {
                task.setStatus(newStatus);
                statusChanged = true;
            }
            if (task.getCompletedAt() == null) {
                task.setCompletedAt(LocalDateTime.now());
                statusChanged = true;
            }
        }
        taskMapper.updateById(task);
        if (pendingCount == 0 && statusChanged) {
            taskScheduleService.scheduleTask(task);
        }
    }

    /**
     * 【获取任务进度VO】
     * 设计思路：
     * - 将Task实体转成前端需要的进度展示对象TaskProgressVO
     * - 使用Builder模式：字段多的时候链式调用比全参构造函数可读性高
     * - percentage计算：注意除0保护，totalCount为0时返回0%
     */
    public TaskProgressVO getTaskProgress(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        return TaskProgressVO.builder()
                .taskNo(task.getTaskNo())
                .status(task.getStatus())
                .totalCount(task.getTotalCount())
                .completedCount(task.getCompletedCount())
                .failedCount(task.getFailedCount())
                .totalAiCount(task.getTotalAiCount())
                .totalQuestionCount(task.getTotalQuestionCount())
                .percentage(task.getTotalCount() > 0 ?
                        (task.getCompletedCount().doubleValue() / task.getTotalCount()) * 100 : 0)
                .errorMsg(task.getErrorMsg())
                .createdAt(task.getCreatedAt())
                .completedAt(task.getCompletedAt())
                .build();
    }

    /**
     * 【获取任务结果列表VO】
     * 设计思路：
     * 1. 这是结果页的核心数据组装方法，逻辑较复杂：
     *    a. 查出TaskAi建立平台编码→显示名映射
     *    b. 尝试从缓存报告中取AI分析过的情感结果（比规则引擎更准）
     *    c. 遍历每个TaskResult，做三件事：
     *       i. 判断答案是否提到了自主品牌（findFirstBrandIndex）
     *       ii. 情感分析（优先用报告缓存，否则调用SentimentUtils规则匹配）
     *       iii. 组装TaskResultVO
     * 2. queryTime兼容逻辑：completedAt为空但状态已是终态，退回用createdAt当查询时间
     * 3. screenshotUrls归一化：Minio返回的路径格式可能有多种，统一转成/api/file/xxx格式供前端访问
     */
    public List<TaskResultVO> getTaskResults(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        List<TaskAi> taskAis = taskAiMapper.selectByTaskNo(taskNo);
        Map<String, String> platformNameMap = new HashMap<>();
        for (TaskAi ai : taskAis) {
            platformNameMap.put(ai.getAiPlatform(), ai.getAiDisplayName());
        }

        String selfBrand = task.getBrandName();

        Map<String, String> aiSentimentMapFromReport = new HashMap<>();
        try {
            if (task.getReportJson() != null && !task.getReportJson().isEmpty()) {
                com.geo.dto.AnalysisReportResponse cachedReport = objectMapper.readValue(task.getReportJson(), com.geo.dto.AnalysisReportResponse.class);
                if (cachedReport.getAiSentimentMap() != null) {
                    aiSentimentMapFromReport = cachedReport.getAiSentimentMap();
                }
            }
        } catch (Exception e) {
            log.debug("解析缓存报告的AI情感数据失败: {}", e.getMessage());
        }

        List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);
        List<TaskResultVO> voList = new ArrayList<>();
        for (TaskResult r : results) {
            boolean mentioned = false;
            String sentiment = "none";
            String sentimentSource = "word";
            try {
                if (selfBrand != null && !selfBrand.isEmpty()) {
                    String answerText = r.getAnswerText() != null ? r.getAnswerText() : "";
                    String combined = answerText.toLowerCase();
                    mentioned = findFirstBrandIndex(combined, selfBrand) >= 0;

                    String resultIdKey = String.valueOf(r.getId());
                    if (aiSentimentMapFromReport.containsKey(resultIdKey)) {
                        sentiment = aiSentimentMapFromReport.get(resultIdKey);
                        sentimentSource = "ai";
                    } else {
                        sentiment = SentimentUtils.analyzeBrandSentiment(answerText, selfBrand);
                    }
                }
            } catch (Exception ignored) {
                mentioned = false;
                sentiment = "none";
            }

            LocalDateTime queryTime = r.getCompletedAt();
            if (queryTime == null) {
                String status = r.getStatus();
                if (ResultStatus.SUCCESS.name().equals(status)
                        || ResultStatus.FAILED.name().equals(status)
                        || ResultStatus.TIMEOUT.name().equals(status)) {
                    queryTime = r.getCreatedAt();
                }
            }

            TaskResultVO vo = TaskResultVO.builder()
                    .id(r.getId())
                    .aiPlatform(r.getAiPlatform())
                    .aiDisplayName(platformNameMap.getOrDefault(r.getAiPlatform(), r.getAiPlatform()))
                    .questionText(r.getQuestionText())
                    .answerText(r.getAnswerText())
                    .thinkingContent(r.getThinkingContent())
                    .sourceInfo(r.getSourceInfo())
                    .screenshotUrls(parseScreenshotUrls(r.getScreenshotUrls()))
                    .status(r.getStatus())
                    .errorMsg(r.getErrorMsg())
                    .durationMs(r.getDurationMs())
                    .rpaRetryCount(r.getRpaRetryCount())
                    .createdAt(r.getCreatedAt())
                    .completedAt(r.getCompletedAt())
                    .queryTime(queryTime)
                    .showStatus(mentioned ? "SUCCESS" : "NOT_FOUND")
                    .brand(mentioned && selfBrand != null ? selfBrand : "-")
                    .sentiment(sentiment)
                    .sentimentSource(sentimentSource)
                    .build();
            voList.add(vo);
        }
        return voList;
    }

    /**
     * 【解析截图URL JSON并归一化】
     * 设计思路：截图URL在DB里是JSON数组字符串，需要解析后逐个做路径归一化
     */
    private java.util.List<String> parseScreenshotUrls(String screenshotUrlsJson) {
        if (screenshotUrlsJson == null || screenshotUrlsJson.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        try {
            java.util.List<String> rawUrls = objectMapper.readValue(screenshotUrlsJson, new TypeReference<List<String>>() {});
            java.util.List<String> normalizedUrls = new java.util.ArrayList<>(rawUrls.size());
            for (String url : rawUrls) {
                normalizedUrls.add(normalizeScreenshotUrl(url));
            }
            return normalizedUrls;
        } catch (JsonProcessingException e) {
            log.warn("解析截图URLs失败: {}", screenshotUrlsJson, e);
            return new java.util.ArrayList<>();
        }
    }

    /**
     * 【截图URL归一化】
     * 设计思路：
     * - 历史包袱：不同时期存入的URL格式不统一（完整Minio地址、相对路径、bucket前缀等）
     * - 统一目标：全部转成/api/file/geo-bucket/xxx格式，由网关的FileProxyController统一代理访问
     * - 这样做的好处：前端不用关心Minio真实地址，网关可以加鉴权、CDN、限流等
     */
    private String normalizeScreenshotUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        if (url.startsWith("/api/file/")) {
            return url;
        }
        int idx = url.indexOf("/geo-bucket/");
        if (idx >= 0) {
            return "/api/file" + url.substring(idx);
        }
        if (url.contains("geo-bucket")) {
            int bucketIdx = url.indexOf("geo-bucket");
            return "/api/file/" + url.substring(bucketIdx);
        }
        return url;
    }

    /**
     * 【更新单个任务结果（单截图版本）】
     * 设计思路：
     * - RPA回调的第一个版本，只支持1张截图
     * - 为了兼容老的RPA Worker保留这个方法
     * - 内部把单URL包装成List调用updateTaskResultWithMultipleScreenshots的逻辑其实可以抽取，但这里保持简单
     * - 最后调用updateTaskProgress：原子任务完成后立即推动主任务进度更新
     */
    @Transactional
    public void updateTaskResult(Long taskResultId, String answerText, String screenshotUrl,
                                 String status, String errorMsg, Long durationMs) {
        TaskResult result = taskResultMapper.selectById(taskResultId);
        if (result == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务结果不存在");
        }

        result.setAnswerText(answerText);
        if (screenshotUrl != null && !screenshotUrl.isEmpty()) {
            List<String> urls = new ArrayList<>();
            urls.add(screenshotUrl);
            try {
                result.setScreenshotUrls(objectMapper.writeValueAsString(urls));
            } catch (JsonProcessingException e) {
                log.error("序列化截图URL失败", e);
            }
        }
        result.setStatus(status);
        result.setErrorMsg(errorMsg);
        result.setDurationMs(durationMs);
        result.setCompletedAt(LocalDateTime.now());
        taskResultMapper.updateById(result);

        updateTaskProgress(result.getTaskId());
    }

    /**
     * 【更新单个任务结果（多截图版本）】
     * 设计思路：新版RPA Worker回调，支持多张截图（更真实地还原AI回答页面）
     */
    @Transactional
    public void updateTaskResultWithMultipleScreenshots(Long taskResultId, String answerText,
                                                        java.util.List<String> screenshotUrls,
                                                        String status, String errorMsg, Long durationMs) {
        TaskResult result = taskResultMapper.selectById(taskResultId);
        if (result == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务结果不存在");
        }

        result.setAnswerText(answerText);
        if (screenshotUrls != null && !screenshotUrls.isEmpty()) {
            try {
                result.setScreenshotUrls(objectMapper.writeValueAsString(screenshotUrls));
            } catch (JsonProcessingException e) {
                log.error("序列化截图URLs失败", e);
            }
        }
        result.setStatus(status);
        result.setErrorMsg(errorMsg);
        result.setDurationMs(durationMs);
        result.setCompletedAt(LocalDateTime.now());
        taskResultMapper.updateById(result);

        updateTaskProgress(result.getTaskId());
    }

    /**
     * 【更新任务主表进度】
     * 设计思路：
     * - RPA回调每个TaskResult完成后都会调用这个方法
     * - 与syncTaskProgressFromResults几乎相同，但处理完成后的逻辑有差异：
     *   这里会重新calculateNextRunTime计算周期任务下次时间
     * - 性能考虑：每次只查该taskId下的results，数据量可控（一个任务最多1000问题×10平台=10000条）
     */
    @Transactional
    public void updateTaskProgress(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }

        List<TaskResult> results = taskResultMapper.selectByTaskId(taskId);
        long successCount = 0;
        long failedCount = 0;
        long pendingCount = 0;
        for (TaskResult r : results) {
            String status = r.getStatus();
            if (ResultStatus.SUCCESS.name().equals(status)) {
                successCount++;
            } else if (ResultStatus.FAILED.name().equals(status) || ResultStatus.TIMEOUT.name().equals(status)) {
                failedCount++;
            } else {
                pendingCount++;
            }
        }

        task.setTotalCount(results.size());
        task.setCompletedCount((int) successCount);
        task.setFailedCount((int) failedCount);

        if (pendingCount == 0) {
            if (failedCount == 0) {
                task.setStatus(TaskStatus.COMPLETED.name());
            } else if (successCount > 0) {
                task.setStatus(TaskStatus.PARTIAL_FAILED.name());
            } else {
                task.setStatus(TaskStatus.FAILED.name());
            }
            task.setCompletedAt(LocalDateTime.now());
            task.setNextRunTime(calculateNextRunTime(task.getExecutionFrequency()));
        }

        taskMapper.updateById(task);

        if (pendingCount == 0) {
            taskScheduleService.scheduleTask(task);
        }
    }

    /**
     * 【重试失败的任务】
     * 设计思路：便利方法，默认只重试FAILED/TIMEOUT状态的
     */
    @Transactional
    public Task retryFailedTasks(String taskNo) {
        return retryTasks(taskNo, null);
    }

    /**
     * 【重试所有任务】
     * 设计思路：便利方法，重试全部（包括已成功的），用于AI模型升级后想重新跑一遍对比效果
     */
    @Transactional
    public Task retryAllTasks(String taskNo) {
        return retryTasks(taskNo, "all");
    }

    /**
     * 【停止任务】
     * 设计思路：
     * - 软停止：不杀进程（RPA在远程机器上也杀不了），而是改状态
     * - 子任务处理：把PENDING/RUNNING的子任务标记为FAILED+错误信息
     * - 正在RUNNING的那几个Worker跑完后回调时可能会"复活"结果状态，这是可接受的最终一致性
     */
    @Transactional
    public Task stopTask(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }
        if (TaskStatus.PROCESSING.name().equals(task.getStatus()) || TaskStatus.PENDING.name().equals(task.getStatus())
                || TaskStatus.PAUSED.name().equals(task.getStatus())) {
            task.setStatus(TaskStatus.CANCELLED.name());
            task.setErrorMsg("任务已手动终止");
            taskMapper.updateById(task);
        }
        List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);
        for (TaskResult result : results) {
            if (ResultStatus.PENDING.name().equals(result.getStatus()) || ResultStatus.RUNNING.name().equals(result.getStatus())) {
                result.setStatus(ResultStatus.FAILED.name());
                result.setErrorMsg("任务已终止");
                taskResultMapper.updateById(result);
            }
        }
        return task;
    }

    /**
     * 【暂停任务】
     * 设计思路：
     * - 与stop不同：pause是"可恢复的暂停"，状态置为PAUSED
     * - 效果：RPA分发器会跳过PAUSED状态的任务，不再领新子任务
     * - 已经在跑的子任务不会被中断，会自然跑完（注释已说明）
     */
    @Transactional
    public Task pauseTask(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }
        if (!TaskStatus.PROCESSING.name().equals(task.getStatus())
                && !TaskStatus.PENDING.name().equals(task.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前状态不允许暂停");
        }
        task.setStatus(TaskStatus.PAUSED.name());
        taskMapper.updateById(task);
        log.info("暂停任务: taskNo={}", taskNo);
        return task;
    }

    /**
     * 【恢复任务】
     * 设计思路：从PAUSED回到PROCESSING，RPA分发器会重新开始认领这个任务的子任务
     */
    @Transactional
    public Task resumeTask(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }
        if (!TaskStatus.PAUSED.name().equals(task.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前状态不允许恢复");
        }
        task.setStatus(TaskStatus.PROCESSING.name());
        taskMapper.updateById(task);
        log.info("恢复任务: taskNo={}", taskNo);
        return task;
    }

    /**
     * 【通用重试方法】
     * 设计思路：
     * 1. 核心方法，被retryFailedTasks/retryAllTasks/retrySuccessTasks包装调用
     * 2. filter参数控制重试范围：
     *    - null：只重试FAILED/TIMEOUT（默认用户最常用场景）
     *    - "all"：重试所有
     *    - "success"：只重试已成功的（对比模型升级效果）
     * 3. 重试处理逻辑：
     *    - 清空之前的answer/截图/错误信息
     *    - 重置createdAt为当前时间（让兜底扫描逻辑认为是"新任务"）
     *    - rpaRetryCount++（用于统计和界面展示这个小任务反复跑了多少次）
     * 4. 只把需要重试的那部分results发给RPA，不是整个任务全发，节省资源
     */
    @Transactional
    public Task retryTasks(String taskNo, String filter) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);
        List<TaskResult> retryResults = new ArrayList<>();

        for (TaskResult result : results) {
            String status = result.getStatus();
            boolean shouldRetry = false;
            if ("all".equals(filter)) {
                shouldRetry = true;
            } else if ("success".equals(filter)) {
                shouldRetry = ResultStatus.SUCCESS.name().equals(status);
            } else if (ResultStatus.FAILED.name().equals(status) || ResultStatus.TIMEOUT.name().equals(status)) {
                shouldRetry = true;
            }

            if (shouldRetry) {
                result.setStatus(ResultStatus.PENDING.name());
                result.setErrorMsg(null);
                result.setAnswerText(null);
                result.setScreenshotUrls(null);
                result.setDurationMs(null);
                result.setCompletedAt(null);
                result.setCreatedAt(LocalDateTime.now());
                result.setRpaRetryCount(result.getRpaRetryCount() != null ? result.getRpaRetryCount() + 1 : 1);
                taskResultMapper.updateById(result);
                retryResults.add(result);
            }
        }

        if (retryResults.isEmpty()) {
            if ("all".equals(filter)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "没有任务需要重试");
            } else if ("success".equals(filter)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "没有成功的任务需要重试");
            } else {
                throw new BusinessException(ResultCode.BAD_REQUEST, "没有失败的任务需要重试");
            }
        }

        task.setStatus(TaskStatus.PROCESSING.name());
        task.setCompletedAt(null);
        task.setNextRunTime(calculateNextRunTime(task.getExecutionFrequency()));
        taskMapper.updateById(task);
        taskScheduleService.scheduleTask(task);

        List<String> competitorsList = new ArrayList<>();
        if (task.getCompetitors() != null && !task.getCompetitors().isEmpty()) {
            try {
                competitorsList = objectMapper.readValue(task.getCompetitors(), new TypeReference<List<String>>() {});
            } catch (JsonProcessingException e) {
                log.warn("解析竞争对手列表失败", e);
            }
        }
        dispatchToRpa(taskNo, retryResults, task.getBrandName(), task.getProductName(), competitorsList, "single", true);

        log.info("重试任务: taskNo={}, retryCount={}, filter={}", taskNo, retryResults.size(), filter);
        return task;
    }

    /**
     * 【重试单个TaskResult】
     * 设计思路：精细重试，用户在界面上勾选某几条不满意的结果单独重跑
     * - 其他成功的结果不受影响
     * - 如果主任务之前已完成，会被重新拉回PROCESSING状态
     */
    @Transactional
    public Task retrySpecificTaskResult(Long taskResultId) {
        TaskResult result = taskResultMapper.selectById(taskResultId);
        if (result == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务结果不存在");
        }

        result.setStatus(ResultStatus.PENDING.name());
        result.setErrorMsg(null);
        result.setAnswerText(null);
        result.setScreenshotUrls(null);
        result.setDurationMs(null);
        result.setCompletedAt(null);
        result.setCreatedAt(LocalDateTime.now());
        result.setRpaRetryCount(result.getRpaRetryCount() != null ? result.getRpaRetryCount() + 1 : 1);
        taskResultMapper.updateById(result);

        Task task = taskMapper.selectById(result.getTaskId());
        if (task != null && !TaskStatus.PROCESSING.name().equals(task.getStatus())) {
            task.setStatus(TaskStatus.PROCESSING.name());
            task.setCompletedAt(null);
            task.setNextRunTime(calculateNextRunTime(task.getExecutionFrequency()));
            taskMapper.updateById(task);
            taskScheduleService.scheduleTask(task);
        }

        List<TaskResult> retryResults = new ArrayList<>();
        retryResults.add(result);

        List<String> competitorsList = new ArrayList<>();
        if (task != null && task.getCompetitors() != null && !task.getCompetitors().isEmpty()) {
            try {
                competitorsList = objectMapper.readValue(task.getCompetitors(), new TypeReference<List<String>>() {});
            } catch (JsonProcessingException e) {
                log.warn("解析竞争对手列表失败", e);
            }
        }
        dispatchToRpa(result.getTaskNo(), retryResults, task != null ? task.getBrandName() : "", task != null ? task.getProductName() : null, competitorsList, "single", true);

        log.info("重试单个任务: taskResultId={}, taskNo={}", taskResultId, result.getTaskNo());
        return task;
    }

    /**
     * 【重试已成功的任务】
     * 设计思路：便利方法，常用于AI模型更新后重新跑已有的成功结果对比效果
     */
    @Transactional
    public Task retrySuccessTasks(String taskNo) {
        return retryTasks(taskNo, "success");
    }

    /**
     * 【删除单个任务结果】
     * 设计思路：
     * - 正在RUNNING的不能删（防止删了之后RPA回调找不到记录报错）
     * - 删除后同步扣减主任务的统计计数
     * - reportJson清空：删除了结果后之前的分析报告就不完整了，强制让分析服务重新生成
     */
    @Transactional
    public Task deleteTaskResult(Long taskResultId) {
        TaskResult result = taskResultMapper.selectById(taskResultId);
        if (result == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务结果不存在");
        }

        if (ResultStatus.RUNNING.name().equals(result.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该问题正在执行中，无法删除");
        }

        Long taskId = result.getTaskId();
        String taskNo = result.getTaskNo();

        taskResultMapper.deleteById(taskResultId);

        Task task = taskMapper.selectById(taskId);
        if (task != null) {
            if (task.getTotalCount() != null && task.getTotalCount() > 0) {
                task.setTotalCount(task.getTotalCount() - 1);
            }
            if (task.getCompletedCount() != null && task.getCompletedCount() > 0 &&
                (ResultStatus.SUCCESS.name().equals(result.getStatus()))) {
                task.setCompletedCount(task.getCompletedCount() - 1);
            }
            if (task.getFailedCount() != null && task.getFailedCount() > 0 &&
                (ResultStatus.FAILED.name().equals(result.getStatus()) || ResultStatus.TIMEOUT.name().equals(result.getStatus()))) {
                task.setFailedCount(task.getFailedCount() - 1);
            }
            if (task.getReportJson() != null && !task.getReportJson().isEmpty()) {
                task.setReportJson(null);
            }

            taskMapper.updateById(task);
            updateTaskProgress(taskId);
        }

        log.info("删除单个任务结果: taskResultId={}, taskNo={}", taskResultId, taskNo);
        return task;
    }

    /**
     * 【批量删除任务结果】
     * 设计思路：
     * - 先遍历校验一遍：只要有一个正在RUNNING的，整个批量删除都不执行
     * - 这样避免删了一半才发现报错，造成"部分删除"的尴尬状态
     * - 最后统一调用一次updateTaskProgress重新计算进度（比一个个扣减更简单可靠）
     */
    @Transactional
    public Task deleteTaskResults(List<Long> taskResultIds) {
        if (taskResultIds == null || taskResultIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请选择要删除的问题");
        }

        String taskNo = null;
        Long taskId = null;

        for (Long id : taskResultIds) {
            TaskResult result = taskResultMapper.selectById(id);
            if (result == null) {
                continue;
            }
            if (ResultStatus.RUNNING.name().equals(result.getStatus())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "选中的问题中有正在执行的，无法删除");
            }
            if (taskNo == null) {
                taskNo = result.getTaskNo();
                taskId = result.getTaskId();
            }
        }

        for (Long id : taskResultIds) {
            taskResultMapper.deleteById(id);
        }

        Task task = null;
        if (taskId != null) {
            task = taskMapper.selectById(taskId);
            if (task != null) {
                if (task.getReportJson() != null && !task.getReportJson().isEmpty()) {
                    task.setReportJson(null);
                    taskMapper.updateById(task);
                }
                updateTaskProgress(taskId);
            }
        }

        log.info("批量删除任务结果: count={}, taskNo={}", taskResultIds.size(), taskNo);
        return task;
    }

    /**
     * 【按状态重试任务】
     * 设计思路：更灵活的重试入口，比如用户想重试所有RUNNING状态卡住的任务（超时但状态没更新的）
     */
    @Transactional
    public Task retryTasksByStatus(String taskNo, String status) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);
        List<TaskResult> retryResults = new ArrayList<>();

        for (TaskResult result : results) {
            String resultStatus = result.getStatus();
            if (status.equalsIgnoreCase(resultStatus)) {
                result.setStatus(ResultStatus.PENDING.name());
                result.setErrorMsg(null);
                result.setAnswerText(null);
                result.setScreenshotUrls(null);
                result.setDurationMs(null);
                result.setCompletedAt(null);
                result.setCreatedAt(LocalDateTime.now());
                result.setRpaRetryCount(result.getRpaRetryCount() != null ? result.getRpaRetryCount() + 1 : 1);
                taskResultMapper.updateById(result);
                retryResults.add(result);
            }
        }

        if (retryResults.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "没有" + status + "状态的任务需要重试");
        }

        task.setStatus(TaskStatus.PROCESSING.name());
        task.setCompletedAt(null);
        taskMapper.updateById(task);

        List<String> competitorsList = new ArrayList<>();
        if (task.getCompetitors() != null && !task.getCompetitors().isEmpty()) {
            try {
                competitorsList = objectMapper.readValue(task.getCompetitors(), new TypeReference<List<String>>() {});
            } catch (JsonProcessingException e) {
                log.warn("解析竞争对手列表失败", e);
            }
        }
        dispatchToRpa(taskNo, retryResults, task.getBrandName(), task.getProductName(), competitorsList, "single", true);

        log.info("按状态重试任务: taskNo={}, retryCount={}, status={}", taskNo, retryResults.size(), status);
        return task;
    }

    /**
     * 【获取品牌排名分析】
     * 设计思路：
     * 1. 这是系统的"智能分析核心"之一，从AI回答文本中提取品牌提及顺序并统计排名
     * 2. 缓存策略：任务终态后结果不会变了，命中缓存直接返回
     * 3. 分析算法分两层：
     *    a. 单回答层（analyzeAnswerBrandRanking）：每个回答中各品牌首次出现位置 → 排名
     *    b. 聚合层（本方法后半段）：汇总所有回答统计每个品牌的提及率、Top1率、Top3率等
     * 4. 统计指标（对竞品分析非常有价值）：
     *    - mentionCount/mentionRate：被提到的次数/比例 → AI是否"认识"这个品牌
     *    - firstCount/firstRate：排第一的次数/比例 → 品牌心智占有率（AI第一个想到的）
     *    - top3Count/top3Rate：排前三的次数/比例 → 第一梯队成员
     *    - rankDistribution：排名分布直方图 → 完整分布形态
     */
    public TaskRankingVO getTaskRankings(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        boolean isFinal = TaskStatus.COMPLETED.name().equals(task.getStatus())
                || TaskStatus.FAILED.name().equals(task.getStatus())
                || TaskStatus.PARTIAL_FAILED.name().equals(task.getStatus())
                || TaskStatus.CANCELLED.name().equals(task.getStatus());
        if (isFinal) {
            TaskRankingVO cached = rankingCache.get(taskNo);
            if (cached != null) {
                log.debug("使用缓存排名: taskNo={}", taskNo);
                return cached;
            }
        }

        String selfBrand = task.getBrandName();
        List<String> competitorBrands = parseCompetitors(task.getCompetitors());

        List<String> allBrands = new ArrayList<>();
        if (selfBrand != null && !selfBrand.trim().isEmpty()) {
            allBrands.add(selfBrand.trim());
        }
        allBrands.addAll(competitorBrands);

        List<TaskAi> taskAis = taskAiMapper.selectByTaskNo(taskNo);
        Map<String, String> platformNameMap = new HashMap<>();
        for (TaskAi ai : taskAis) {
            platformNameMap.put(ai.getAiPlatform(), ai.getAiDisplayName());
        }

        List<TaskQuestion> questions = taskQuestionMapper.selectByTaskNo(taskNo);
        Map<Long, TaskQuestion> questionMap = new HashMap<>();
        for (TaskQuestion q : questions) {
            questionMap.put(q.getId(), q);
        }

        List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);
        results = results.stream()
                .sorted(Comparator.comparing((TaskResult r) -> {
                    TaskQuestion q = questionMap.get(r.getTaskQuestionId());
                    return q != null && q.getSortOrder() != null ? q.getSortOrder() : Integer.MAX_VALUE;
                }).thenComparing(r -> r.getCreatedAt() != null ? r.getCreatedAt() : LocalDateTime.MIN))
                .collect(Collectors.toList());

        List<TaskRankingVO.QuestionRanking> questionRankings = new ArrayList<>();
        Map<String, List<Integer>> brandAllRanks = new LinkedHashMap<>();
        for (String brand : allBrands) {
            brandAllRanks.put(brand, new ArrayList<>());
        }

        for (TaskResult r : results) {
            TaskRankingVO.QuestionRanking qr = new TaskRankingVO.QuestionRanking();
            qr.setResultId(r.getId());
            qr.setQuestionId(r.getTaskQuestionId());
            qr.setQuestionText(r.getQuestionText());
            qr.setAiPlatform(r.getAiPlatform());
            qr.setAiDisplayName(platformNameMap.getOrDefault(r.getAiPlatform(), r.getAiPlatform()));
            qr.setStatus(r.getStatus());
            qr.setCompletedAt(r.getCompletedAt());

            TaskQuestion tq = questionMap.get(r.getTaskQuestionId());
            if (tq != null) {
                qr.setSortOrder(tq.getSortOrder());
            }

            List<TaskRankingVO.BrandRankItem> brandRankings = analyzeAnswerBrandRanking(
                    r.getAnswerText(), r.getThinkingContent(), allBrands, selfBrand, brandAllRanks);
            qr.setBrandRankings(brandRankings);
            questionRankings.add(qr);
        }

        int validAnswers = (int) results.stream()
                .filter(r -> ResultStatus.SUCCESS.name().equals(r.getStatus()))
                .count();

        List<TaskRankingVO.BrandAggregatedRanking> aggregatedRankings = new ArrayList<>();
        for (String brand : allBrands) {
            boolean isSelf = brand.equals(selfBrand);
            List<Integer> ranks = brandAllRanks.getOrDefault(brand, Collections.emptyList());

            TaskRankingVO.BrandAggregatedRanking agg = new TaskRankingVO.BrandAggregatedRanking();
            agg.setBrandName(brand);
            agg.setIsSelfBrand(isSelf);
            agg.setAllRanks(new ArrayList<>(ranks));

            int mentionCount = ranks.size();
            int firstCount = 0, top3Count = 0, top5Count = 0;
            Map<Integer, Integer> distribution = new TreeMap<>();
            for (Integer rank : ranks) {
                if (rank != null) {
                    distribution.merge(rank, 1, Integer::sum);
                    if (rank == 1) firstCount++;
                    if (rank <= 3) top3Count++;
                    if (rank <= 5) top5Count++;
                }
            }

            agg.setMentionCount(mentionCount);
            agg.setFirstCount(firstCount);
            agg.setTop3Count(top3Count);
            agg.setTop5Count(top5Count);
            agg.setMentionRate(validAnswers > 0 ? Math.round((mentionCount * 10000.0) / validAnswers) / 100.0 : 0.0);
            agg.setFirstRate(validAnswers > 0 ? Math.round((firstCount * 10000.0) / validAnswers) / 100.0 : 0.0);
            agg.setTop3Rate(validAnswers > 0 ? Math.round((top3Count * 10000.0) / validAnswers) / 100.0 : 0.0);
            agg.setTop5Rate(validAnswers > 0 ? Math.round((top5Count * 10000.0) / validAnswers) / 100.0 : 0.0);
            agg.setRankDistribution(distribution);

            aggregatedRankings.add(agg);
        }

        TaskRankingVO vo = new TaskRankingVO();
        vo.setTaskNo(taskNo);
        vo.setTitle(task.getTitle());
        vo.setBrandName(selfBrand);
        vo.setCompetitorBrands(competitorBrands);
        vo.setQuestionRankings(questionRankings);
        vo.setAggregatedRankings(aggregatedRankings);
        vo.setTotalAnswers(results.size());
        vo.setValidAnswers(validAnswers);

        if (isFinal) {
            rankingCache.put(taskNo, vo);
            log.debug("排名已缓存: taskNo={}, questionCount={}", taskNo, questionRankings.size());
        }
        return vo;
    }

    /**
     * 【分析单个回答中的品牌排名】
     * 设计思路：
     * 1. 排名算法核心：在AI回答文本中找每个品牌名"第一次出现的位置"
     * 2. 出现越早，排名越靠前（AI第一个提到的品牌 = 它心中的No.1）
     * 3. 这个假设的合理性：AI回答推荐类问题时，通常把最强的候选人放最前面
     * 4. 匹配上下文片段（matchedTextMap）：前端高亮展示，让用户知道匹配的具体位置
     *
     * @param answerText     AI回答正文
     * @param thinkingContent AI思考过程（预留，目前主要看answerText）
     * @param allBrands      所有待匹配品牌列表
     * @param selfBrand      自主品牌名
     * @param brandAllRanks   全局排名收集Map（用于后续聚合统计）
     * @return 该回答下各品牌的排名列表
     */
    private List<TaskRankingVO.BrandRankItem> analyzeAnswerBrandRanking(
            String answerText, String thinkingContent,
            List<String> allBrands, String selfBrand,
            Map<String, List<Integer>> brandAllRanks) {

        String combined = (answerText != null ? answerText : "");

        if (combined.trim().isEmpty()) {
            List<TaskRankingVO.BrandRankItem> empty = new ArrayList<>();
            for (String brand : allBrands) {
                empty.add(new TaskRankingVO.BrandRankItem(brand, brand.equals(selfBrand), null, -1, null));
            }
            return empty;
        }

        String lowerCombined = combined.toLowerCase();
        Map<String, Integer> firstIndexMap = new LinkedHashMap<>();
        Map<String, String> matchedTextMap = new LinkedHashMap<>();

        for (String brand : allBrands) {
            if (brand == null || brand.isEmpty()) continue;
            int idx = findFirstBrandIndex(lowerCombined, brand);
            if (idx >= 0) {
                firstIndexMap.put(brand, idx);
                int matchLen = Math.max(4, brand.length());
                int contextLen = 8;
                int snippetStart = Math.max(0, idx - contextLen);
                int snippetEnd = Math.min(combined.length(), idx + matchLen + contextLen);
                String snippet = combined.substring(snippetStart, snippetEnd);
                if (snippetStart > 0) snippet = "…" + snippet;
                if (snippetEnd < combined.length()) snippet = snippet + "…";
                matchedTextMap.put(brand, snippet);
            }
        }

        List<Map.Entry<String, Integer>> sorted = firstIndexMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .collect(Collectors.toList());

        Map<String, Integer> brandRank = new LinkedHashMap<>();
        int position = 1;
        for (Map.Entry<String, Integer> entry : sorted) {
            brandRank.put(entry.getKey(), position++);
            brandAllRanks.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(brandRank.get(entry.getKey()));
        }

        List<TaskRankingVO.BrandRankItem> result = new ArrayList<>();
        for (String brand : allBrands) {
            Integer rank = brandRank.get(brand);
            Integer firstIndex = firstIndexMap.get(brand);
            String matched = matchedTextMap.get(brand);
            boolean isSelf = brand.equals(selfBrand);

            if (rank == null) {
                result.add(new TaskRankingVO.BrandRankItem(brand, isSelf, null, -1, null));
            } else {
                result.add(new TaskRankingVO.BrandRankItem(brand, isSelf, rank, firstIndex, matched));
            }
        }

        return result;
    }

    /**
     * 【查找品牌在文本中首次出现的位置】
     * 设计思路：
     * - 简单的indexOf忽略大小写匹配
     * - 目前是基础版，可以后续升级为：
     *   1. 品牌别名表（如"华为"→"HUAWEI"→"Huawei"都算）
     *   2. 正则词边界匹配（避免"小米"匹配到"小米粥"这种误伤）
     */
    private int findFirstBrandIndex(String text, String brand) {
        if (text == null || brand == null || brand.isEmpty()) return -1;

        String lowerText = text.toLowerCase();
        String lowerBrand = brand.toLowerCase();

        return lowerText.indexOf(lowerBrand);
    }

    /**
     * 【解析竞争对手品牌JSON字符串】
     * 设计思路：
     * - 优先按JSON解析（标准格式）
     * - JSON失败后fallback到分隔符解析（手工录入的脏数据兼容）
     * - 分隔符支持：中英文逗号、顿号、分号、各种空白符
     * - 容错策略：解析失败不报错，尽量多的提取可用品牌
     */
    private List<String> parseCompetitors(String competitorsStr) {
        List<String> result = new ArrayList<>();
        if (competitorsStr == null || competitorsStr.isEmpty()) {
            return result;
        }
        try {
            List<String> parsed = objectMapper.readValue(competitorsStr, new TypeReference<List<String>>() {});
            if (parsed != null) {
                for (String s : parsed) {
                    if (s != null && !s.trim().isEmpty()) {
                        result.add(s.trim());
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.debug("竞对品牌JSON解析失败，尝试按逗号分割: {}", e.getMessage());
        }
        String[] parts = competitorsStr.split("[,，、;；\\s]+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 【计算周期任务下次执行时间】
     * 设计思路：
     * - 目前只支持daily/weekly两种
     * - 默认返回null表示"单次任务，不再执行"
     * - 后续可扩展："hourly"、"monthly"、自定义cron表达式等
     */
    public LocalDateTime calculateNextRunTime(String executionFrequency) {
        if (executionFrequency == null) {
            return null;
        }
        switch (executionFrequency) {
            case "daily":
                return LocalDateTime.now().plusDays(1);
            case "weekly":
                return LocalDateTime.now().plusWeeks(1);
            default:
                return null;
        }
    }

    /**
     * 【兜底扫描 - 重新调度到期的周期任务】
     * 设计思路：
     * 1. 这是"DB兜底扫描"入口，配合内存调度器的"双保险"机制：
     *    - 内存调度器（TaskScheduleService）：精确触发，性能好，但服务重启会丢失
     *    - DB扫描（本方法）：定时扫描数据库中到了next_run_time的任务，弥补重启/崩溃漏掉的
     * 2. 扫描条件：
     *    - next_run_time不为空（有周期的才扫）
     *    - next_run_time <= 当前时间（已到期）
     *    - 状态是终态（COMPLETED/PARTIAL_FAILED/FAILED）：只有跑完的才需要触发下一轮
     * 3. 触发方式：调用retryAllTasks（相当于把这个任务所有子任务重置后重新跑）
     */
    @Transactional
    public int rescheduleDueTasks() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.isNotNull("next_run_time");
        wrapper.le("next_run_time", LocalDateTime.now());
        wrapper.in("status",
                TaskStatus.COMPLETED.name(),
                TaskStatus.PARTIAL_FAILED.name(),
                TaskStatus.FAILED.name());
        List<Task> dueTasks = taskMapper.selectList(wrapper);
        if (dueTasks == null || dueTasks.isEmpty()) {
            return 0;
        }

        int triggered = 0;
        for (Task task : dueTasks) {
            try {
                retryAllTasks(task.getTaskNo());
                task.setNextRunTime(calculateNextRunTime(task.getExecutionFrequency()));
                taskMapper.updateById(task);
                taskScheduleService.scheduleTask(task);
                triggered++;
                log.info("周期调度触发任务重跑: taskNo={}, frequency={}",
                        task.getTaskNo(), task.getExecutionFrequency());
            } catch (Exception e) {
                log.error("周期任务触发失败: taskNo={}", task.getTaskNo(), e);
            }
        }
        return triggered;
    }

    /**
     * 【判断任务是否已到执行时间】
     * 设计思路：
     * - 单次任务(single)永不到期
     * - next_run_time <= now() 就是到期了
     * - 由精确调度器(rescheduleDueSingleTask)调用前的前置校验
     */
    private boolean isTaskDue(Task task) {
        if (task == null || task.getNextRunTime() == null) return false;
        String freq = task.getExecutionFrequency();
        if (freq == null || "single".equals(freq)) return false;
        return !task.getNextRunTime().isAfter(LocalDateTime.now());
    }
}