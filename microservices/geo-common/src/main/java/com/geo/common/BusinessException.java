package com.geo.common;

/**
 * 【业务异常类】
 *
 * 设计思路：
 * 1. 为什么要继承RuntimeException而不是Exception？
 *    - RuntimeException（非受检异常）：方法上不需要声明throws，调用者也不用强制try-catch
 *    - Exception（受检异常）：每个方法都要声明throws，代码会很啰嗦
 *    - 我们用全局异常处理器（GlobalExceptionHandler）统一捕获，所以用RuntimeException更优雅
 *
 * 2. 异常处理流程：
 *    Service层发现业务有问题 → throw new BusinessException(...)
 *    → 抛到Controller层不需要处理
 *    → GlobalExceptionHandler统一捕获，封装成Result.fail()返回给前端
 *
 * 3. 三个构造方法覆盖不同场景：
 *    - 只传ResultCode：用枚举里预设好的message
 *    - ResultCode + 自定义message：枚举不够用时，自定义更精确的错误描述
 *    - code + message：完全自定义（用得少，主要方便扩展）
 */
public class BusinessException extends RuntimeException {

    // 业务错误码
    private final int code;

    /**
     * 构造方法1：最常用，只传ResultCode枚举
     * 示例：throw new BusinessException(ResultCode.TASK_NOT_FOUND);
     */
    public BusinessException(ResultCode resultCode) {
        // message用枚举里预设好的
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    /**
     * 构造方法2：传ResultCode + 自定义错误信息
     * 示例：throw new BusinessException(ResultCode.BAD_REQUEST, "任务正在运行中，无法删除");
     * 设计思路：同一个BAD_REQUEST（400）可能有很多种具体原因，这时候就需要自定义message
     */
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    /**
     * 构造方法3：完全自定义code和message
     * 一般用上面两个就够了，这个是留着特殊场景用的
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    // 获取错误码
    public int getCode() {
        return code;
    }
}