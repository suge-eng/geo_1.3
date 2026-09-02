package com.geo.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 【全局异常处理器】
 *
 * 设计思路（核心设计模式：AOP面向切面编程）：
 * 1. 为什么需要这个类？
 *    - 不想在每个Controller方法里都写一大堆 try-catch
 *    - 统一处理所有异常，统一返回 Result 格式
 *    - 避免没捕获的异常把堆栈信息直接暴露给用户（不安全）
 *
 * 2. @RestControllerAdvice 注解的作用：
 *    - 相当于给所有Controller加了个"统一拦截器"
 *    - Controller抛出的任何异常都会被这里的@ExceptionHandler拦截
 *    - 不用在每个Controller里写重复的异常处理代码
 *
 * 3. 处理优先级：
 *    先匹配最具体的异常类型（比如BusinessException），
 *    都匹配不到才走最后的 Exception（兜底），
 *    这个匹配规则是Spring帮我们做的，越具体优先级越高。
 *
 * 4. 日志记录策略：
 *    - 业务异常（BusinessException）：打warn就行，这是正常的业务提示
 *    - 系统异常（Exception）：打error并打印堆栈，这是代码出bug了要排查
 *
 * 5. 踩过的坑：HttpMessageNotWritableException
 *    之前有个接口要返回 image/png 图片，但 Spring 容器里没有注册能把图片写出去的
 *    HttpMessageConverter（消息转换器），结果 Spring 在把返回体序列化时抛出了
 *    HttpMessageNotWritableException。这个异常不属于上面任何一类，会直接落到兜底的
 *    handleException 里；而它的 HTTP 状态码此时已经被 Spring 锁成 200 了，于是前端
 *    拿到"HTTP 200（成功）"却带着一个错误 body，非常隐蔽、难排查。
 *    教训：凡是返回非 JSON 内容的接口（图片、文件下载流等），要单独确认对应的消息
 *    转换器是否齐全，不能指望全局兜底"兜住就没事"。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 日志对象，使用slf4j门面模式（以后可以随意切换logback/log4j等实现）
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理【业务异常】BusinessException
     * 设计思路：
     * - 这是我们自己定义的异常，属于"预期内的错误"（比如用户没选AI平台）
     * - 所以HTTP状态码还是200，但body里的code是业务错误码
     * - 日志级别是warn（警告），不是error（错误），因为这是正常业务提示
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        // 把异常的code和message包装成Result返回
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理【@Valid注解校验失败】- JSON请求体校验
     * 设计思路：
     * - 当Controller参数用了@RequestBody @Valid，校验失败会抛这个异常
     * - 比如@NotEmpty字段为空，就会触发
     * - 把所有字段错误拼起来，一起返回给用户看
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        // 把所有校验失败的字段的错误信息拿出来，用逗号拼起来
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return Result.fail(ResultCode.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理【表单参数绑定失败】- form表单提交校验
     * 设计思路：
     * - 跟上面那个类似，但这个是form表单方式提交（非JSON）时的校验异常
     * - 同样是把所有错误拼起来返回
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return Result.fail(ResultCode.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理【方法参数约束违反】- URL路径参数/查询参数的校验
     * 设计思路：
     * - 比如@RequestParam @NotBlank String name，传空值就会抛这个
     * - 又一种Spring校验异常，三个异常覆盖了不同场景
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining(", "));
        return Result.fail(ResultCode.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理【异步请求超时】
     * 设计思路：
     * - 某些接口处理时间长用异步，超时了返回友好提示
     * - 不直接说"超时"，而是说"系统繁忙"，体验更好
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleAsyncTimeout(AsyncRequestTimeoutException e) {
        log.warn("异步请求超时");
        return Result.fail(ResultCode.RATE_LIMIT_EXCEEDED, "系统繁忙，请稍后再试");
    }

    /**
     * 处理【静态资源找不到】
     * 设计思路：
     * - favicon.ico是浏览器自动请求的图标，不存在就直接忽略返回null
     * - 其他资源找不到才返回404错误
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        // 浏览器每次都会请求这个图标，不存在很正常，不用管
        if (e.getMessage() != null && e.getMessage().contains("favicon.ico")) {
            return null;
        }
        log.warn("资源未找到: {}", e.getMessage());
        return Result.fail(ResultCode.NOT_FOUND);
    }

    /**
     * 处理【所有其他异常】- 兜底方案
     * 设计思路：
     * - 上面所有异常都不匹配时走这里
     * - 比如NullPointerException、IndexOutOfBoundsException等代码Bug
     * - 一定要打完整堆栈（log.error带第三个参数e），不然排查Bug找不到原因！
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleException(Exception e) {
        // 这里一定要打error级别的日志并打印堆栈，方便排查线上Bug
        log.error("系统异常", e);
        // 返回给用户的信息尽量友好，不暴露内部堆栈（安全考虑）
        return Result.fail(ResultCode.INTERNAL_ERROR.getCode(), e.getMessage() != null ? e.getMessage() : ResultCode.INTERNAL_ERROR.getMessage());
    }
}