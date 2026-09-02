package com.geo.enums;

/**
 * 【AI平台账号状态枚举】
 *
 * 设计思路：
 * 这个系统是通过多个真实账号去访问各个AI平台的（模拟真实用户行为），
 * 所以每个账号需要有自己的状态来管理调度。
 *
 * 账号池调度策略（简单理解）：
 * 1. 调度时只从 ACTIVE 状态的账号里挑
 * 2. 账号用完额度变 EXHAUSTED，次日重置后再变回 ACTIVE
 * 3. 账号被封了变 BANNED，需要人工处理
 * 4. 平台维护或者账号出问题临时变 MAINTENANCE
 */
public enum AccountStatus {
    // 正常可用：可以被调度去执行任务
    ACTIVE,
    // 已封禁：账号被AI平台封号了，不能用了
    BANNED,
    // 维护中：临时不可用（比如需要换Cookie、平台维护）
    MAINTENANCE,
    // 额度已用完：当天使用次数超了，等第二天重置
    EXHAUSTED
}