package com.geo.enums;

/**
 * 【单个任务结果状态枚举】
 *
 * 设计思路：
 * 1. TaskStatus是整个大任务的状态，ResultStatus是其中每一条子结果的状态
 *    比如：选了2个AI平台+3个问题 = 6条TaskResult，每条都有自己的状态
 *
 * 2. 状态流转：
 *    PENDING → 刚创建，还没被RPA领取
 *    RUNNING → RPA已经领取并正在执行
 *    SUCCESS → 成功拿到了AI的回答
 *    FAILED  → 执行出错（比如网页崩溃）
 *    TIMEOUT → 超过设定时间还没返回结果
 *
 * 3. 区分"FAILED失败"和"TIMEOUT超时"很重要：
 *    - FAILED通常是明确的错误，可以针对性修复
 *    - TIMEOUT可能是网络慢或AI在排队，重试成功率更高
 */
public enum ResultStatus {
    // 待执行：刚创建好，等着RPA来领取
    PENDING,
    // 执行中：RPA已经领取，正在浏览器上操作AI平台
    RUNNING,
    // 成功：拿到了AI的完整回答
    SUCCESS,
    // 失败：执行过程中出现明确错误
    FAILED,
    // 超时：等太久没返回，判定为超时
    TIMEOUT
}