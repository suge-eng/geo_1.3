package com.geo.common;

/**
 * 【统一API响应包装类】
 *
 * 设计思路：
 * 1. 为什么所有接口都要包一层Result？
 *    - 前后端约定统一的返回格式：{code, message, data}
 *    - 前端不用判断是200还是400，统一读code字段就知道成功失败
 *    - 成功/失败返回结构一样，前端处理起来更简单
 *
 * 2. 三个字段的含义：
 *    code    → 状态码，0/200表示成功，其他都是失败
 *    message → 提示信息，可以直接显示给用户
 *    data    → 真正的业务数据（泛型T支持任意类型）
 *
 * 3. 为什么用静态工厂方法（success/fail）而不是new Result()？
 *    - 代码更简洁易读：Result.success(data) 比 new Result<>(200, "ok", data) 清晰多了
 *    - 防止写错code：统一从ResultCode枚举拿，不会有人随手写个123
 *    - 方法重载：支持多种参数组合，按需调用
 *
 * 4. 泛型<T>的作用：
 *    不同接口返回的数据类型不一样（有的返回Task，有的返回List<Task>）
 *    用泛型可以一套代码适配所有返回类型，还能保持类型安全
 */
public class Result<T> {

    // 状态码
    private int code;
    // 提示信息
    private String message;
    // 业务数据（泛型，支持任意类型）
    private T data;

    // ==================== 成功相关的静态方法 ====================

    /**
     * 成功但不需要返回数据（比如删除操作）
     * 示例：return Result.success();
     */
    public static <T> Result<T> success() {
        Result<T> result = new Result<>();
        result.code = ResultCode.SUCCESS.getCode();
        result.message = ResultCode.SUCCESS.getMessage();
        return result;
    }

    /**
     * 成功，带数据返回（最常用）
     * 示例：return Result.success(task);
     */
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.code = ResultCode.SUCCESS.getCode();
        result.message = ResultCode.SUCCESS.getMessage();
        result.data = data;
        return result;
    }

    /**
     * 成功，自定义提示信息，不返回数据
     * 示例：return Result.success("删除成功");
     */
    public static <T> Result<T> success(String message) {
        Result<T> result = new Result<>();
        result.code = ResultCode.SUCCESS.getCode();
        result.message = message;
        return result;
    }

    /**
     * 成功，自定义提示信息 + 数据
     * 示例：return Result.success("创建成功", task);
     */
    public static <T> Result<T> success(String message, T data) {
        Result<T> result = new Result<>();
        result.code = ResultCode.SUCCESS.getCode();
        result.message = message;
        result.data = data;
        return result;
    }

    // ==================== 失败相关的静态方法 ====================

    /**
     * 失败，传ResultCode枚举（最常用）
     * 示例：return Result.fail(ResultCode.TASK_NOT_FOUND);
     */
    public static <T> Result<T> fail(ResultCode resultCode) {
        Result<T> result = new Result<>();
        result.code = resultCode.getCode();
        result.message = resultCode.getMessage();
        return result;
    }

    /**
     * 失败，传ResultCode + 自定义错误信息
     * 示例：return Result.fail(ResultCode.BAD_REQUEST, "自定义的错误原因");
     */
    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        Result<T> result = new Result<>();
        result.code = resultCode.getCode();
        result.message = message;
        return result;
    }

    /**
     * 失败，完全自定义code和message
     * 一般用上面两个就够了
     */
    public static <T> Result<T> fail(int code, String message) {
        Result<T> result = new Result<>();
        result.code = code;
        result.message = message;
        return result;
    }

    // ==================== 普通getter/setter ====================

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}