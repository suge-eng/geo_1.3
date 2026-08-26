package com.geo.common;

public enum ResultCode {

    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    TASK_NOT_FOUND(1001, "任务不存在"),
    TASK_STATUS_INVALID(1002, "任务状态不允许此操作"),
    AI_LIST_EMPTY(1003, "至少选择一个AI平台"),
    QUESTION_EMPTY(1004, "问题列表不能为空"),
    QUESTION_TOO_MANY(1005, "问题数量不能超过50个"),
    QUESTION_TOO_LONG(1006, "单个问题长度不能超过1000字符"),
    AI_PLATFORM_INVALID(1007, "AI平台不支持"),
    RATE_LIMIT_EXCEEDED(1008, "请求频率过高，请稍后再试"),
    RPA_TASK_FAILED(1009, "RPA任务执行失败"),
    ACCOUNT_EXHAUSTED(1010, "AI平台账号额度已用完"),
    DUPLICATE_SUBMIT(1011, "请勿重复提交");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
