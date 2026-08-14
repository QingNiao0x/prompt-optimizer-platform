package com.promptoptimizer.enhancement.application;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 检测原始需求中的常见信息缺口，避免模型把猜测当成项目事实。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class AmbiguityDetector {

    /**
     * 返回需要在增强结果中明确标注的待确认项。
     */
    public List<String> detect(String rawPrompt) {
        String normalized = rawPrompt.trim().toLowerCase(Locale.ROOT);
        List<String> ambiguities = new ArrayList<>();

        if (normalized.length() < 20) {
            ambiguities.add("需求描述较短，需要确认具体业务目标和完成标准。");
        }
        if (containsAny(normalized, "弄个", "搞个", "处理一下", "优化一下", "改一下", "排序")) {
            ambiguities.add("存在模糊动作，需要确认算法、实现范围或期望行为。");
        }
        if (!containsAny(normalized, "输入", "参数", "url", "列表", "文件", "请求", "接口")) {
            ambiguities.add("尚未明确输入来源、参数格式或调用方式。");
        }
        if (!containsAny(normalized, "输出", "返回", "响应", "打印", "生成", "保存")) {
            ambiguities.add("尚未明确输出内容、输出格式或错误返回方式。");
        }
        if (!containsAny(normalized, "测试", "验收", "成功", "完成", "预期")) {
            ambiguities.add("尚未给出可验证的验收标准。");
        }
        return List.copyOf(ambiguities);
    }

    /**
     * 判断文本是否包含任一候选关键词。
     */
    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
