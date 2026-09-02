package com.geo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 【任务超时配置】
 *
 * 设计思路：
 * 有些任务可能一直PROCESSING（比如RPA挂了、子任务卡了），不能让它永远挂着。
 * 所以用定时任务扫描超时任务，把它标记成TIMEOUT。
 *
 * 把配置写在类里，通过@ConfigurationProperties从yml读取，方便不同环境调参。
 * 例如application.yml里配置geo.task.timeout.timeout-minutes: 1440就改成24小时。
 *
 * 默认值：
 * - 每5分钟检查一次
 * - 超过12小时还没完成就算超时
 */
@Configuration
@ConfigurationProperties(prefix = "geo.task.timeout")
public class TaskTimeoutConfig {

    /** 超时检查间隔：每隔多少秒扫一次数据库（默认300秒=5分钟） */
    private int checkIntervalSeconds = 300;

    /** 任务超时阈值：PROCESSING状态持续多少分钟后算超时（默认720分钟=12小时） */
    private int timeoutMinutes = 720;

    public int getCheckIntervalSeconds() {
        return checkIntervalSeconds;
    }

    public void setCheckIntervalSeconds(int checkIntervalSeconds) {
        this.checkIntervalSeconds = checkIntervalSeconds;
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public void setTimeoutMinutes(int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }
}