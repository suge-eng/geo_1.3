package com.geo.common;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 【情感分析工具类】
 *
 * 设计思路（基于关键词的简易情感分析）：
 * 1. 为什么要做情感分析？
 *    当AI回答里提到了自主品牌时，我们需要判断AI说的是好话还是坏话，
 *    这样报告里可以展示"正面评价率"、"负面评价率"等关键指标。
 *
 * 2. 实现原理（最简单的词典匹配法）：
 *    - 准备好"正面词列表"和"负面词列表"
 *    - 在AI回答里统计两种词的数量
 *    - 正面多 → positive（正面），负面多 → negative（负面），差不多 → neutral（中性）
 *
 * 3. 为什么不用AI大模型做情感分析？
 *    - 速度：词典匹配是毫秒级，调大模型要好几秒
 *    - 成本：大模型调用是要花钱的，词典完全免费
 *    - 可控：词典可以手动调整，AI分析的结果有时不可预测
 *    这种"词典+规则"的方案虽然简单，但在业务场景里其实很实用！
 *
 * 4. 进阶规则（让分析更准确）：
 *    - 否定词检测："不好"里的"好"是正面词，但前面有"不"要转成负面
 *    - 转折词检测："虽然XX很好，但是..."，重点是"但是"后面的内容
 */
public class SentimentUtils {

    // ========== 1. 正面词汇表（带权重：出现一次加1分） ==========
    // 设计思路：收集日常评价里常见的正面词，越全越准
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

    // ========== 2. 负面词汇表（出现一次加1分） ==========
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

    // ========== 3. 正面模式（正则匹配，权重更高：一次加2分） ==========
    // 设计思路：有些长表达不是单个词，用正则匹配更准确，给2分说明重要性更高
    public static final List<String> POSITIVE_PATTERNS = Arrays.asList(
            "比较推荐", "个人推荐", "我推荐", "大家推荐", "都推荐",
            "排名第", "排在第", "位列第", "名列前茅", "排名靠前", "排在前面",
            "综合来看", "总的来说", "整体来看", "总体来说", "综上所述",
            "具有.*优势", "具备.*优势", "拥有.*优势", "突出.*优势",
            "深受.*喜爱", "广受.*好评", "备受.*青睐", "用户.*好评",
            "市场份额", "销量", "热销", "爆款", "明星产品", "旗舰",
            "技术领先", "行业领先", "业界领先", "遥遥领先"
    );

    // ========== 4. 否定前缀（检测"不好"、"不推荐"这种反转语义） ==========
    // 设计思路：中文最常见的否定词就是"不"系列，遇到了后面的正面词要转负面
    private static final List<String> NEGATION_PREFIXES = Arrays.asList(
            "不", "不是", "并非", "没", "无", "非"
    );

    // ========== 5. 转折连接词（"但是"后面才是说话人真正想表达的） ==========
    // 设计思路："A很好，但是B不好" → 重点是B，所以要给转折后面的内容加权
    private static final List<String> CONJUNCTION_WORDS = Arrays.asList(
            "但", "但是", "不过", "然而", "可是", "只是", "唯独", "只有",
            "但可惜", "但遗憾", "不过可惜"
    );

    // 需要特殊注意的词（否定词后面跟着这些词要反转）
    private static final List<String> NEGATIVE_TO_WATCH = Arrays.asList(
            "好", "不错", "棒", "赞", "满意", "喜欢", "推荐", "靠谱",
            "值得", "优秀", "出色", "领先", "首选", "最佳", "稳定", "可靠",
            "实用", "强大", "专业", "创新", "方便", "快捷", "高效", "贴心"
    );

    /**
     * 【核心方法：分析品牌情感倾向】
     *
     * 返回值说明：
     *   "positive" → 正面（AI说了很多好话）
     *   "negative" → 负面（AI说了很多坏话）
     *   "neutral"  → 中性（不好不坏）
     *   "none"     → 没提到这个品牌（根本没提，就谈不上情感）
     *
     * 算法步骤：
     *   1. 先检查文本里有没有提到这个品牌，没提到直接返回none
     *   2. 统计正面词数量 + 正面模式匹配加分
     *   3. 统计负面词数量
     *   4. 处理否定词反转（"不好" → 负面）
     *   5. 处理转折词（"但是"后面的内容加权重）
     *   6. 比较分数得出最终结果
     */
    public static String analyzeBrandSentiment(String answerText, String selfBrand) {
        // 品牌为空，没法分析
        if (selfBrand == null || selfBrand.isEmpty()) {
            return "none";
        }
        String text = answerText != null ? answerText : "";
        String lowerText = text.toLowerCase();
        String lowerBrand = selfBrand.toLowerCase();

        // 第一步：先看AI回答里有没有提到我们的品牌，没提到直接返回none
        boolean mentioned = lowerText.contains(lowerBrand);
        if (!mentioned) {
            return "none";
        }

        // 初始化分数
        int positiveScore = 0;
        int negativeScore = 0;

        // 第二步：遍历所有正面词，统计出现次数（每个词加1分）
        for (String word : POSITIVE_WORDS) {
            int idx = 0;
            int count = 0;
            // indexOf循环：找出这个词在文本里出现了多少次
            while ((idx = lowerText.indexOf(word.toLowerCase(), idx)) != -1) {
                count++;
                idx += word.length();
            }
            positiveScore += count;
        }

        // 第三步：遍历所有负面词，统计出现次数
        for (String word : NEGATIVE_WORDS) {
            int idx = 0;
            int count = 0;
            while ((idx = lowerText.indexOf(word.toLowerCase(), idx)) != -1) {
                count++;
                idx += word.length();
            }
            negativeScore += count;
        }

        // 第四步：匹配正面正则模式，每个模式加2分（权重更高）
        for (String pattern : POSITIVE_PATTERNS) {
            try {
                if (Pattern.compile(pattern).matcher(text).find()) {
                    positiveScore += 2;
                }
            } catch (Exception ignored) {
                // 正则写错了也不影响主流程，忽略
            }
        }

        // 第五步：检测否定词带来的语义反转（比如"不好"应该算负面）
        int[] negationAdj = detectNegationAdjustment(lowerText);
        positiveScore += negationAdj[0];
        negativeScore += negationAdj[1];

        // 第六步：检测转折词带来的权重变化（"但是"后面的话更重要）
        int[] conjAdj = detectConjunctionShift(lowerText);
        positiveScore += conjAdj[0];
        negativeScore += conjAdj[1];

        // 第七步：比较分数得出最终结果
        if (negativeScore > positiveScore) {
            return "negative";   // 负面分更高 → 负面评价
        } else if (positiveScore > 0) {
            return "positive";   // 有正面分且比负面高 → 正面评价
        }

        // 都差不多 → 中性
        return "neutral";
    }

    /**
     * 检测否定词调整（"不好"、"不推荐"这种）
     * 返回数组：[0]=正面分调整值，[1]=负面分调整值
     *
     * 设计思路：
     * 找到"不"字，看后面跟着的词是不是正面词，
     * 如果是（比如"不好"），就把正面分减1，负面分加1（反转语义）
     */
    private static int[] detectNegationAdjustment(String lowerText) {
        int posAdjust = 0;
        int negAdjust = 0;

        // 遍历每个否定词
        for (String negation : NEGATION_PREFIXES) {
            String lowerNeg = negation.toLowerCase();
            int idx = 0;
            // 找出所有出现的否定词位置
            while ((idx = lowerText.indexOf(lowerNeg, idx)) != -1) {
                // 取否定词后面的内容
                String after = lowerText.substring(Math.min(idx + lowerNeg.length(), lowerText.length()));
                boolean negFound = false;
                // 看后面是不是跟着某个正面词
                for (String posWord : NEGATIVE_TO_WATCH) {
                    if (after.startsWith(posWord.toLowerCase())) {
                        // 前面是标点符号的话（比如"。不推荐"），否定和词不关联，跳过
                        if (idx > 0 && isPunctuation(lowerText.charAt(idx - 1))) {
                            idx++;
                            continue;
                        }
                        // 找到"不+正面词"组合：减正面分，加负面分
                        negAdjust++;
                        posAdjust--;
                        negFound = true;
                        break;
                    }
                }
                // 根据是否找到决定跳过多少字符，避免重复匹配
                if (negFound) {
                    idx += lowerNeg.length() + 2;
                } else {
                    idx++;
                }
            }
        }
        return new int[]{posAdjust, negAdjust};
    }

    /**
     * 判断字符是不是标点符号
     * 设计思路：标点符号表示一句话结束，前后的否定词和正面词就没关联了
     */
    private static boolean isPunctuation(char c) {
        return c == ',' || c == '，' || c == '。' || c == '.' || c == '!' || c == '！'
                || c == '?' || c == '？' || c == '、' || c == ';';
    }

    /**
     * 检测转折词带来的情感偏移（"但是"后面的内容更重要）
     * 返回数组：[0]=正面分调整值，[1]=负面分调整值
     *
     * 设计思路：
     * 中文表达习惯是"先扬后抑"或"先抑后扬"，
     * "但是/不过/然而"这些词后面的内容才是说话人真正想强调的，
     * 所以转折词后面30个字符范围内的内容权重翻倍
     */
    private static int[] detectConjunctionShift(String lowerText) {
        int posAdjust = 0;
        int negAdjust = 0;

        // 遍历每个转折词
        for (String conj : CONJUNCTION_WORDS) {
            String lowerConj = conj.toLowerCase();
            int idx = 0;
            // 找出每个转折词的位置
            while ((idx = lowerText.indexOf(lowerConj, idx)) != -1) {
                // 取转折词后面30个字符（大概一句话的长度）
                int afterLen = Math.min(lowerText.length() - (idx + lowerConj.length()), 30);
                if (afterLen <= 0) break;
                String after = lowerText.substring(idx + lowerConj.length(),
                        idx + lowerConj.length() + afterLen);

                // 统计转折后面30个字符范围内的正面/负面词数
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

                // 转折后面的负面多 → 大幅加负面分
                if (afterNeg > afterPos) {
                    negAdjust += 2;
                    posAdjust -= 1;
                } else if (afterPos > afterNeg) {
                    // 转折后面的正面多 → 大幅加正面分
                    posAdjust += 2;
                    negAdjust -= 1;
                }

                idx += lowerConj.length();
            }
        }
        return new int[]{posAdjust, negAdjust};
    }
}