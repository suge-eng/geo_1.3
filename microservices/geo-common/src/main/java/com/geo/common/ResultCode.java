package com.geo.common;

/**
 * 【统一响应状态码枚举】
 *
 * 设计思路：
 * 1. 为什么不直接用HTTP状态码？
 *    - HTTP状态码只有几十个，不够业务用（比如"任务不存在"和"账号不存在"是两种不同错误）
 *    - 所以我们在HTTP 200的基础上，再定义一套业务错误码
 *
 * 2. 编码规范：
 *    - 200/400/401/403/404/500：通用错误，跟HTTP状态码保持一致，好记
 *    - 1000开头：业务错误码，每个业务模块自己的特殊错误
 *
 * 3. 好处：
 *    - 前后端约定好，前端可以根据不同的code做不同的提示处理
 *    - 集中管理，不会到处写"1001"、"1002"这种魔法数字
 */
public enum ResultCode {

    // ========== 通用状态码 ==========
    // 操作成功
    SUCCESS(200, "操作成功"),
    // 请求参数有误（格式不对、缺少必填项等）
    BAD_REQUEST(400, "请求参数错误"),
    // 未登录/未授权
    UNAUTHORIZED(401, "未授权"),
    // 登录了但没权限访问
    FORBIDDEN(403, "禁止访问"),
    // 请求的资源找不到
    NOT_FOUND(404, "资源不存在"),
    // 服务器内部出错（异常没处理好）
    INTERNAL_ERROR(500, "服务器内部错误"),

    // ========== 任务模块业务状态码（1000开头） ==========
    // 任务编号在数据库查不到
    TASK_NOT_FOUND(1001, "任务不存在"),
    // 任务状态不允许此操作（比如已经完成的任务不能再提交）
    TASK_STATUS_INVALID(1002, "任务状态不允许此操作"),
    // 创建任务时一个AI都没选
    AI_LIST_EMPTY(1003, "至少选择一个AI平台"),
    // 问题列表是空的
    QUESTION_EMPTY(1004, "问题列表不能为空"),
    // 问题太多了（超过上限）
    QUESTION_TOO_MANY(1005, "问题数量不能超过50个"),
    // 单个问题字数超了
    QUESTION_TOO_LONG(1006, "单个问题长度不能超过1000字符"),
    // 选了不在支持列表里的AI平台
    AI_PLATFORM_INVALID(1007, "AI平台不支持"),
    // 请求太频繁被限流
    RATE_LIMIT_EXCEEDED(1008, "请求频率过高，请稍后再试"),
    // RPA机器人执行任务失败
    RPA_TASK_FAILED(1009, "RPA任务执行失败"),
    // 所有AI账号今天的额度都用完了
    ACCOUNT_EXHAUSTED(1010, "AI平台账号额度已用完"),
    // 防止用户重复点提交按钮
    DUPLICATE_SUBMIT(1011, "请勿重复提交");

    // 状态码数字
    private final int code;
    // 状态码描述信息（可以直接给用户看）
    private final String message;

    // 枚举构造方法
    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    // 获取code
    public int getCode() {
        return code;
    }

    // 获取message
    public String getMessage() {
        return message;
    }
}