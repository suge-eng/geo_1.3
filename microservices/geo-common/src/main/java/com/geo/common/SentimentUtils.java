package com.geo.common;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class SentimentUtils {

    public static final List<String> POSITIVE_WORDS = Arrays.asList(
            "推荐", "优秀", "出色", "好", "不错", "很棒", "好评", "值得", "靠谱", "优势",
            "领先", "首选", "最佳", "赞", "满意", "喜欢", "支持", "认可", "第一",
            "高质量", "稳定", "可靠", "性能好", "性价比高", "实用", "强大", "专业", "创新",
            "舒适", "美观", "耐用", "安全", "智能", "方便", "快捷", "高效", "贴心", "完善",
            "卓越", "非凡", "极致", "精品", "良心", "诚意", "用心", "口碑好", "畅销", "火爆",
            "建议", "可以考虑", "我觉得", "个人觉得", "比较好", "挺好", "蛮好", "深得人心",
            "选择", "选购", "购买", "入手", "力荐", "强推", "推崇",
            "值得买", "值得入手", "值得推荐", "值得拥有", "值得信赖", "非常好", "非常不错",
            "超棒", "超好", "给力", "牛", "厉害", "惊艳", "惊喜", "爱不释手", "满意之选",
            "准确", "精准", "先进", "友好", "丰富", "全面", "均衡", "出色", "出众"
    );

    public static final List<String> NEGATIVE_WORDS = Arrays.asList(
            "不推荐", "不建议", "不喜欢", "不怎么样", "差", "不好", "糟糕", "差评", "失望", "垃圾",
            "烂", "骗", "坑", "问题", "故障", "缺陷", "缺点", "不足", "落后", "次", "劣质",
            "不靠谱", "风险", "投诉", "维权", "虚假", "夸大", "忽悠", "避雷", "劝退", "踩雷",
            "翻车", "割韭菜", "不稳定", "卡顿", "死机", "崩溃", "坏了", "返修", "售后差", "态度差",
            "贵", "不值", "性价比低", "难用", "复杂", "麻烦", "慢", "耗电", "发热", "噪音大",
            "千万别买", "不要买", "别买", "后悔", "踩坑", "被坑", "智商税", "华而不实",
            "一般般", "普通", "平庸", "凑合", "勉强", "不尽人意", "失望透顶", "不行",
            "不准确", "不精准", "误差", "错误", "bug", "缺陷", "瑕疵", "遗憾"
    );

    public static final List<String> POSITIVE_PATTERNS = Arrays.asList(
            "比较推荐", "个人推荐", "我推荐", "大家推荐", "都推荐",
            "排名第", "排在第", "位列第", "名列前茅", "排名靠前", "排在前面",
            "综合来看", "总的来说", "整体来看", "总体来说", "综上所述",
            "具有.*优势", "具备.*优势", "拥有.*优势", "突出.*优势",
            "深受.*喜爱", "广受.*好评", "备受.*青睐", "用户.*好评",
            "市场份额", "销量", "热销", "爆款", "明星产品", "旗舰",
            "技术领先", "行业领先", "业界领先", "遥遥领先"
    );

    private static final List<String> NEGATION_PREFIXES = Arrays.asList(
            "不", "不是", "并非", "没", "无", "非"
    );

    private static final List<String> CONJUNCTION_WORDS = Arrays.asList(
            "但", "但是", "不过", "然而", "可是", "只是", "唯独", "只有",
            "但可惜", "但遗憾", "不过可惜"
    );

    private static final List<String> NEGATIVE_TO_WATCH = Arrays.asList(
            "好", "不错", "棒", "赞", "满意", "喜欢", "推荐", "靠谱",
            "值得", "优秀", "出色", "领先", "首选", "最佳", "稳定", "可靠",
            "实用", "强大", "专业", "创新", "方便", "快捷", "高效", "贴心"
    );

    public static String analyzeBrandSentiment(String answerText, String selfBrand) {
        if (selfBrand == null || selfBrand.isEmpty()) {
            return "none";
        }
        String text = answerText != null ? answerText : "";
        String lowerText = text.toLowerCase();
        String lowerBrand = selfBrand.toLowerCase();

        boolean mentioned = lowerText.contains(lowerBrand);
        if (!mentioned) {
            return "none";
        }

        int positiveScore = 0;
        int negativeScore = 0;

        for (String word : POSITIVE_WORDS) {
            int idx = 0;
            int count = 0;
            while ((idx = lowerText.indexOf(word.toLowerCase(), idx)) != -1) {
                count++;
                idx += word.length();
            }
            positiveScore += count;
        }

        for (String word : NEGATIVE_WORDS) {
            int idx = 0;
            int count = 0;
            while ((idx = lowerText.indexOf(word.toLowerCase(), idx)) != -1) {
                count++;
                idx += word.length();
            }
            negativeScore += count;
        }

        for (String pattern : POSITIVE_PATTERNS) {
            try {
                if (Pattern.compile(pattern).matcher(text).find()) {
                    positiveScore += 2;
                }
            } catch (Exception ignored) {}
        }

        int[] negationAdj = detectNegationAdjustment(lowerText);
        positiveScore += negationAdj[0];
        negativeScore += negationAdj[1];

        int[] conjAdj = detectConjunctionShift(lowerText);
        positiveScore += conjAdj[0];
        negativeScore += conjAdj[1];

        if (negativeScore > positiveScore) {
            return "negative";
        } else if (positiveScore > 0) {
            return "positive";
        }

        return "neutral";
    }

    private static int[] detectNegationAdjustment(String lowerText) {
        int posAdjust = 0;
        int negAdjust = 0;

        for (String negation : NEGATION_PREFIXES) {
            String lowerNeg = negation.toLowerCase();
            int idx = 0;
            while ((idx = lowerText.indexOf(lowerNeg, idx)) != -1) {
                String after = lowerText.substring(Math.min(idx + lowerNeg.length(), lowerText.length()));
                boolean negFound = false;
                for (String posWord : NEGATIVE_TO_WATCH) {
                    if (after.startsWith(posWord.toLowerCase())) {
                        if (idx > 0 && isPunctuation(lowerText.charAt(idx - 1))) {
                            idx++;
                            continue;
                        }
                        negAdjust++;
                        posAdjust--;
                        negFound = true;
                        break;
                    }
                }
                if (negFound) {
                    idx += lowerNeg.length() + 2;
                } else {
                    idx++;
                }
            }
        }
        return new int[]{posAdjust, negAdjust};
    }

    private static boolean isPunctuation(char c) {
        return c == ',' || c == '，' || c == '。' || c == '.' || c == '!' || c == '！'
                || c == '?' || c == '？' || c == '、' || c == ';';
    }

    private static int[] detectConjunctionShift(String lowerText) {
        int posAdjust = 0;
        int negAdjust = 0;

        for (String conj : CONJUNCTION_WORDS) {
            String lowerConj = conj.toLowerCase();
            int idx = 0;
            while ((idx = lowerText.indexOf(lowerConj, idx)) != -1) {
                int afterLen = Math.min(lowerText.length() - (idx + lowerConj.length()), 30);
                if (afterLen <= 0) break;
                String after = lowerText.substring(idx + lowerConj.length(),
                        idx + lowerConj.length() + afterLen);

                int afterPos = 0;
                int afterNeg = 0;

                for (String word : POSITIVE_WORDS) {
                    int wIdx = 0;
                    int wCount = 0;
                    while ((wIdx = after.indexOf(word.toLowerCase(), wIdx)) != -1) {
                        wCount++;
                        wIdx += word.length();
                    }
                    afterPos += wCount;
                }

                for (String word : NEGATIVE_WORDS) {
                    int wIdx = 0;
                    int wCount = 0;
                    while ((wIdx = after.indexOf(word.toLowerCase(), wIdx)) != -1) {
                        wCount++;
                        wIdx += word.length();
                    }
                    afterNeg += wCount;
                }

                if (afterNeg > afterPos) {
                    negAdjust += 2;
                    posAdjust -= 1;
                } else if (afterPos > afterNeg) {
                    posAdjust += 2;
                    negAdjust -= 1;
                }

                idx += lowerConj.length();
            }
        }
        return new int[]{posAdjust, negAdjust};
    }
}