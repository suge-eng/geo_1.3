package com.geo.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 【Excel批量导入创建任务请求DTO】
 *
 * 设计思路：
 * 1. 跟普通TaskSubmitRequest的区别：问题不是前端直接传列表，
 *    而是用户上传一个Excel文件，问题在Excel里。
 * 2. 所以这个DTO里没有questions字段，问题是通过MultipartFile（文件上传）传的。
 * 3. 其他字段跟普通创建任务是一样的（选AI平台、填标题、品牌等）。
 *
 * 使用流程：
 * 1. 前端上传Excel文件 + 这个DTO的参数
 * 2. ExcelParseService负责解析Excel里的每一行变成一个问题
 * 3. 解析完成后组装成TaskSubmitRequest，走正常的创建任务流程
 */
public class ExcelTaskSubmitRequest {

    /** 选择的AI平台代码列表 */
    @NotEmpty(message = "至少选择一个AI平台")
    @Size(max = 10, message = "AI平台数量不能超过10个")
    private List<String> aiPlatforms;

    /** 任务标题 */
    @NotEmpty(message = "项目名称不能为空")
    @Size(max = 200, message = "标题长度不能超过200字符")
    private String title;

    /** 自主品牌名称 */
    @NotEmpty(message = "自主品牌名不能为空")
    @Size(max = 100, message = "自主品牌名长度不能超过100字符")
    private String brandName;

    /** 竞争对手品牌列表（最多5个） */
    @Size(max = 5, message = "竞争对手品牌数量不能超过5个")
    private List<String> competitors;

    /** 执行频率，默认单次 */
    private String executionFrequency = "single";

    /** 失败是否重试，默认false */
    private Boolean retryOnFailure = false;

    public List<String> getAiPlatforms() { return aiPlatforms; }
    public void setAiPlatforms(List<String> aiPlatforms) { this.aiPlatforms = aiPlatforms; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }
    public List<String> getCompetitors() { return competitors; }
    public void setCompetitors(List<String> competitors) { this.competitors = competitors; }
    public String getExecutionFrequency() { return executionFrequency; }
    public void setExecutionFrequency(String executionFrequency) { this.executionFrequency = executionFrequency; }
    public Boolean getRetryOnFailure() { return retryOnFailure; }
    public void setRetryOnFailure(Boolean retryOnFailure) { this.retryOnFailure = retryOnFailure; }
}