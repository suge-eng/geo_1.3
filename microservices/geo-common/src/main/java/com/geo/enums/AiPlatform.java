package com.geo.enums;

/**
 * 【AI平台枚举】
 *
 * 设计思路：
 * 1. 为什么需要 code 和 displayName 两个字段？
 *    - code：英文代码，存在数据库里、程序内部比较用（短，稳定）
 *    - displayName：中文显示名，返回给前端在页面上展示给用户看
 *
 * 2. 为什么区分 _APP 后缀？
 *    - 不带后缀：网页版（Web端），比如通过浏览器访问 doubao.com
 *    - 带 _APP 后缀：手机APP版，需要通过手机模拟器/真机操作
 *    同一品牌网页版和APP版给出的回答可能不一样，所以要分别对比
 *
 * 3. fromCode静态方法的作用：
 *    前端传过来"doubao"字符串，我们要转成枚举对象来使用，
 *    遍历所有枚举值找到匹配的返回，找不到返回null表示不支持
 */
public enum AiPlatform {

    // 豆包（字节跳动）- 网页版
    DOUBAO("doubao", "豆包"),
    // 豆包 - 手机APP版
    DOUBAO_APP("doubao_app", "豆包APP"),
    // DeepSeek（深度求索）- 网页版
    DEEPSEEK("deepseek", "DeepSeek"),
    // DeepSeek - 手机APP版
    DEEPSEEK_APP("deepseek_app", "DeepSeek APP"),
    // 通义千问（阿里）- 网页版
    QIANWEN("qianwen", "通义千问"),
    // 通义千问 - 手机APP版
    QIANWEN_APP("qianwen_app", "通义千问 APP"),
    // 腾讯元宝（腾讯）- 网页版
    TENCENT("tencent", "腾讯元宝"),
    // 腾讯元宝 - 手机APP版
    TENCENT_APP("tencent_app", "腾讯元宝APP"),
    // Kimi（月之暗面）- 网页版
    KIMI("kimi", "Kimi"),
    // Kimi - 手机APP版
    KIMI_APP("kimi_app", "Kimi APP"),
    // 文心一言（百度）- 网页版
    WENXIN("wenxin", "文心一言"),
    // 文心一言 - 手机APP版
    WENXIN_APP("wenxin_app", "文心一言APP");

    // 平台英文代码（存数据库、内部传参用）
    private final String code;
    // 中文展示名（给用户在页面上看）
    private final String displayName;

    // 枚举构造方法，每个枚举常量都要传code和displayName
    AiPlatform(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    // 获取code
    public String getCode() {
        return code;
    }

    // 获取中文展示名
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 根据code字符串查找对应的枚举对象
     * 设计思路：
     * - 前端一般只会传字符串code，我们需要转成枚举来用
     * - 返回null表示这个code不在我们的支持列表里
     * - 用equalsIgnoreCase忽略大小写，更友好
     */
    public static AiPlatform fromCode(String code) {
        // code为null直接返回null，避免空指针
        if (code == null) {
            return null;
        }
        // values()拿到所有枚举常量，遍历找匹配的
        for (AiPlatform platform : values()) {
            if (platform.code.equalsIgnoreCase(code)) {
                return platform;
            }
        }
        // 没找到返回null
        return null;
    }
}