package com.geo.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 【创建任务请求DTO】
 *
 * 设计思路：
 * 1. DTO（Data Transfer Object）= 数据传输对象，专门用来接收前端传过来的请求参数
 * 2. 为什么不用Task实体直接接收？
 *    - 前端传的参数结构和数据库表结构不一定一样（比如questions是列表，实体里没有）
 *    - 校验注解（@NotEmpty/@Size）直接加在DTO上，进Controller前就自动校验
 *    - 实体是跟数据库绑定的，改实体可能影响数据库，而DTO随便改不影响
 *
 * 3. 校验注解的作用：
 *    - @NotEmpty：不能为null且不能为空集合/字符串
 *    - @Size：限制集合大小或字符串长度
 *    - message：校验失败时返回给用户的提示文字
 *    - 这些注解会被GlobalExceptionHandler里的MethodArgumentNotValidException捕获处理
 */
public class TaskSubmitRequest {

    /** 选择的AI平台代码列表，如["doubao", "kimi"] */
    @NotEmpty(message = "至少选择一个AI平台")
    @Size(max = 10, message = "AI平台数量不能超过10个")
    private List<String> aiPlatforms;

    /** 用户输入的问题列表 */
    @NotEmpty(message = "问题列表不能为空")
    @Size(max = 1000, message = "问题数量不能超过1000个")
    private List<String> questions;

    /** 任务标题，用户自定义 */
    @NotEmpty(message = "项目名称不能为空")
    @Size(max = 200, message = "标题长度不能超过200字符")
    private String title;

    /** 自主品牌名称（要分析的目标品牌） */
    @NotEmpty(message = "自主品牌名不能为空")
    @Size(max = 100, message = "自主品牌名长度不能超过100字符")
    private String brandName;

    /** 具体单品名（可选），比如"华为Mate 60 Pro" */
    @Size(max = 200, message = "单品名称长度不能超过200字符")
    private String productName;

    /** 竞争对手品牌列表（可选，最多5个） */
    @Size(max = 5, message = "竞争对手品牌数量不能超过5个")
    private List<String> competitors;

    /** 执行频率：single=单次，daily=每天，weekly=每周，默认只跑一次 */
    private String executionFrequency = "single";

    /** 失败时是否自动重试，默认false不重试 */
    private Boolean retryOnFailure = false;

    /** 任务范围：LOCAL=普通任务（预留字段，未来扩展GLOBAL全局分析任务用） */
    private String scope = "LOCAL";

    /** 白名单URL列表（可选），RPA搜索引用来源时只统计这些域名的引用 */
    private List<String> whitelistUrls;

    public List<String> getAiPlatforms() { return aiPlatforms; }
    public void setAiPlatforms(List<String> aiPlatforms) { this.aiPlatforms = aiPlatforms; }
    public List<String> getQuestions() { return questions; }
    public void setQuestions(List<String> questions) { this.questions = questions; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public List<String> getCompetitors() { return competitors; }
    public void setCompetitors(List<String> competitors) { this.competitors = competitors; }
    public String getExecutionFrequency() { return executionFrequency; }
    public void setExecutionFrequency(String executionFrequency) { this.executionFrequency = executionFrequency; }
    public Boolean getRetryOnFailure() { return retryOnFailure; }
    public void setRetryOnFailure(Boolean retryOnFailure) { this.retryOnFailure = retryOnFailure; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public List<String> getWhitelistUrls() { return whitelistUrls; }
    public void setWhitelistUrls(List<String> whitelistUrls) { this.whitelistUrls = whitelistUrls; }
}