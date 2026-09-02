package com.geo.dto;

/**
 * 【RPA机器人回调请求DTO】
 *
 * 设计思路：
 * 1. RPA（机器人流程自动化）Worker在浏览器里把AI回答爬下来后，
 *    会调后端的回调接口，把结果通过这个DTO传回来。
 *
 * 2. 为什么叫"回调"？
 *    - 不是同步请求返回结果，而是RPA跑完了"主动通知"我们
 *    - 流程：后端派发任务 → RPA去执行（可能几分钟甚至更久） → RPA调回这个接口通知结果
 *    - 这是典型的异步处理模式（异步+回调），避免接口超时
 *
 * 3. 为什么同时有screenshotUrl和screenshotUrls？
 *    - screenshotUrl：老版本单张截图（兼容历史代码）
 *    - screenshotUrls：新版本支持多张截图（比如AI回答很长，滚屏截了好几张）
 *    - 后端两个都接收，优先用screenshotUrls，没有就用screenshotUrl兜底
 */
public class RpaCallbackRequest {

    // 对应TaskResult表的主键ID（告诉后端这是哪条任务的结果）
    private Long taskResultId;
    // AI回答的正文内容
    private String answerText;
    // AI的思考过程（比如豆包的"思考中..."部分）
    private String thinkingContent;
    // AI回答中引用的来源信息（带链接的参考文献）
    private String sourceInfo;
    // 单张截图URL（兼容老版本）
    private String screenshotUrl;
    // 多张截图URL列表（推荐用这个）
    private java.util.List<String> screenshotUrls;
    // 结果状态：SUCCESS / FAILED / TIMEOUT
    private String status;
    // 错误信息（失败/超时时传）
    private String errorMsg;
    // 总执行耗时（毫秒）
    private Long durationMs;

    public Long getTaskResultId() { return taskResultId; }
    public void setTaskResultId(Long taskResultId) { this.taskResultId = taskResultId; }
    public String getAnswerText() { return answerText; }
    public void setAnswerText(String answerText) { this.answerText = answerText; }
    public String getThinkingContent() { return thinkingContent; }
    public void setThinkingContent(String thinkingContent) { this.thinkingContent = thinkingContent; }
    public String getSourceInfo() { return sourceInfo; }
    public void setSourceInfo(String sourceInfo) { this.sourceInfo = sourceInfo; }
    public String getScreenshotUrl() { return screenshotUrl; }
    public void setScreenshotUrl(String screenshotUrl) { this.screenshotUrl = screenshotUrl; }
    public java.util.List<String> getScreenshotUrls() { return screenshotUrls; }
    public void setScreenshotUrls(java.util.List<String> screenshotUrls) { this.screenshotUrls = screenshotUrls; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
}