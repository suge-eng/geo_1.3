package com.geo.enums;

public enum AiPlatform {

    DOUBAO("doubao", "豆包"),
    DOUBAO_APP("doubao_app", "豆包APP"),
    DEEPSEEK("deepseek", "DeepSeek"),
    DEEPSEEK_APP("deepseek_app", "DeepSeek APP"),
    QIANWEN("qianwen", "通义千问"),
    QIANWEN_APP("qianwen_app", "通义千问 APP"),
    TENCENT("tencent", "腾讯元宝"),
    TENCENT_APP("tencent_app", "腾讯元宝APP"),
    KIMI("kimi", "Kimi"),
    KIMI_APP("kimi_app", "Kimi APP"),
    WENXIN("wenxin", "文心一言"),
    WENXIN_APP("wenxin_app", "文心一言APP");

    private final String code;
    private final String displayName;

    AiPlatform(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static AiPlatform fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (AiPlatform platform : values()) {
            if (platform.code.equalsIgnoreCase(code)) {
                return platform;
            }
        }
        return null;
    }
}
