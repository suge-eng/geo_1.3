package com.geo.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class ExcelTaskSubmitRequest {

    @NotEmpty(message = "至少选择一个AI平台")
    @Size(max = 10, message = "AI平台数量不能超过10个")
    private List<String> aiPlatforms;

    @NotEmpty(message = "项目名称不能为空")
    @Size(max = 200, message = "标题长度不能超过200字符")
    private String title;

    @NotEmpty(message = "自主品牌名不能为空")
    @Size(max = 100, message = "自主品牌名长度不能超过100字符")
    private String brandName;

    @Size(max = 5, message = "竞争对手品牌数量不能超过5个")
    private List<String> competitors;

    private String executionFrequency = "single";

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
