package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 为有明确标签的事实建立有限的对象与属性范围，防止单个类别的值跨对象复用。
 * 这里只识别可核对的文字关系；不凭文件名、相似度或模型自报类别猜测对象归属。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanningFactScope {
    private static final Pattern QUESTION_GRAMMAR = words(
            "应如何", "应该如何", "按什么标准", "什么标准", "哪段时间", "哪一省", "哪一市", "哪些人",
            "是哪段", "采用什么", "使用什么", "采用哪种", "使用哪种", "偏好哪种", "具体是", "具体",
            "分别", "请问", "请", "采用", "使用", "偏好", "适用", "覆盖", "面向", "来自", "取自", "从哪里获取",
            "获取", "哪里", "哪种", "哪个", "哪些", "什么", "如何", "怎样", "怎么", "谁",
            "应该", "需要", "划分", "分组", "标准", "范围", "按", "的", "是", "应");
    private static final Pattern GENERIC_SCOPE = Pattern.compile(
            "^(?:本次|这次|当前|现有|研究|分析|统计|观察|目标|用户提供|提供|需要分析|覆盖|具体)*$");
    private static final Pattern PREFIX_QUESTION_END = Pattern.compile("(?:" + words("采用什么", "使用什么", "采用哪种",
            "使用哪种", "适用哪个", "是哪种", "是什么", "使用", "采用", "来自", "哪个", "什么").pattern() + ")$");
    private static final Map<PlanningFactCategory, Pattern> PROPERTIES = Arrays.stream(PlanningFactCategory.values())
            .collect(Collectors.toUnmodifiableMap(category -> category, PlanningFactScope::propertyPattern));

    private PlanningFactScope() { }

    /** 保留命名对象、子属性和新条件；无法识别属性时返回 null，不能作为已知答案删除问题。 */
    static String question(PlanningFactCategory category, String text) {
        if (!PROPERTIES.get(category).matcher(text).find()) return null;
        return heading(category, text);
    }

    /** 在同一完整语句内截取属性标题，不把前一句的任务描述当作事实对象。 */
    static String labeled(PlanningFactCategory category, String text, int valueStart) {
        String before = text.substring(0, valueStart);
        int boundary = Math.max(Math.max(before.lastIndexOf('\n'), before.lastIndexOf('。')),
                Math.max(before.lastIndexOf('；'), before.lastIndexOf(';')));
        return heading(category, before.substring(boundary + 1));
    }

    /** 无明确标签的资料只在能定位同类别属性时识别范围；不能把一条取值当作全项目事实。 */
    static String evidence(PlanningFactCategory category, String text) {
        var match = PROPERTIES.get(category).matcher(text);
        if (!match.find()) return null;
        return labeled(category, text, match.end());
    }

    /** 只复用相同对象与相同子属性的明确事实；空范围只与空范围匹配。 */
    static boolean same(String questionScope, String factScope) {
        return questionScope != null && factScope != null && questionScope.equals(factScope);
    }

    private static String heading(PlanningFactCategory category, String text) {
        String value = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        var property = PROPERTIES.get(category).matcher(value);
        if (!property.find()) return null;
        String prefix = questionPrefix(value.substring(0, property.start()));
        String suffix = queryGrammar(PROPERTIES.get(category).matcher(value.substring(property.end())).replaceAll(""));
        // 数据/文件是属性前的通用修饰语，只能在末尾移除，不能改写“数据服务”等真实对象名。
        if (category == PlanningFactCategory.DATA_FORMAT || category == PlanningFactCategory.DATA_SOURCE) {
            prefix = prefix.replaceFirst("(?:的)?(?:数据|文件|材料|资料)$", "");
        }
        if (GENERIC_SCOPE.matcher(prefix).matches()) prefix = "";
        String attribute = "";
        // 年龄、城乡和性别均属于 POPULATION，但不能互相回答。
        if (category == PlanningFactCategory.POPULATION) {
            String label = property.group();
            if (label.contains("年龄")) attribute = "年龄";
            else if (label.contains("城乡")) attribute = "城乡";
            else if (label.contains("性别")) attribute = "性别";
        }
        return prefix + attribute + (suffix.isEmpty() ? "" : "\u0000" + suffix);
    }

    /** 只清除问句语法；研究所、统计服务等对象名中的领域词必须保留。 */
    private static String queryGrammar(String text) {
        return QUESTION_GRAMMAR.matcher(text).replaceAll("")
                .replaceAll("[\\s，。；：？、“”‘’()?;,:\"']+", "");
    }

    /** 属性前保留完整对象名，只消除末尾询问短语和所属助词，不能全局删除“标准”等业务词。 */
    private static String questionPrefix(String text) {
        String value = text.replaceAll("[\\s，。；：？、“”‘’()?;,:\"']+", "").replaceFirst("^请问", "");
        var query = PREFIX_QUESTION_END.matcher(value);
        while (query.find()) {
            value = value.substring(0, query.start());
            query = PREFIX_QUESTION_END.matcher(value);
        }
        return value.replaceFirst("的$", "");
    }

    /** 属性词只用于去除已识别的提问维度，不能删除对象、数值、单位或附加适用条件。 */
    private static Pattern propertyPattern(PlanningFactCategory category) {
        return switch (category) {
            case REGION -> words("研究地区", "研究范围", "地区范围", "覆盖地区", "地区", "区域");
            case AUDIENCE -> words("目标读者", "目标受众", "面向学生", "读者", "受众");
            case JURISDICTION -> words("适用法域", "司法辖区", "法域");
            case DATA_SOURCE -> words("数据来源", "资料来源", "数据源", "来源", "来自", "取自", "从哪里获取");
            case DATA_FORMAT -> words("数据格式", "文件格式", "材料格式", "格式", "类型");
            case DISEASE_CATEGORY -> words("疾病亚类", "病种分类", "疾病分类", "亚类定义", "疾病", "病种", "亚类", "分类", "定义", "口径");
            case POPULATION -> words("人群划分", "研究对象", "目标人群", "年龄组", "年龄", "城乡划分", "城乡", "性别分组", "性别", "人群", "患者");
            case ANALYSIS_TOOL -> words("分析工具", "统计软件", "编程语言", "工具偏好", "工具", "软件", "语言", "编程");
            case ANALYSIS_METHOD -> words("分析方法", "研究方法", "分解方法", "方法学", "方法", "模型");
            case OUTPUT_FORMAT -> words("输出格式", "交付格式", "输出内容", "输出", "交付", "格式", "形式", "内容");
            case ACCEPTANCE_CRITERIA -> words("验收标准", "成功标准", "评价标准", "判断标准", "验收", "成功", "评价", "判断", "条件", "指标");
            case TIME_RANGE -> words("时间范围", "研究期间", "分析期间", "观察期间", "起止时间", "时间段", "期间", "年份", "时间");
            case BUSINESS_RULE -> words("业务规则", "规则", "条件", "阈值");
        };
    }

    private static Pattern words(String... values) {
        return Pattern.compile(Arrays.stream(values).sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).collect(Collectors.joining("|")));
    }
}
