package com.promptoptimizer.provider.domain;

import com.promptoptimizer.common.security.SensitiveValueScanner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 自然正文的独立保真审阅，只定位并修订当前稿中的片段，不建立新事实、来源或用户确认。
 * 来源及正文引文只是可复核定位，语义判断仍需真实对照；无问题时保留原稿及其任务记录。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalPromptFidelityReview(List<Issue> issues, NaturalTaskUnderstanding taskUnderstanding) {
    /** 审阅只处理少量可定位问题；超出容量明确失败，不截取后声称已全部修正。 */
    private static final int MAX_ISSUES = 16;
    /** 单个引用或替换片段的UTF-16长度上限，与既有任务理解字段一致，不截断用户正文。 */
    private static final int MAX_FIELD_LENGTH = 20_000;
    private static final SensitiveValueScanner SENSITIVE = new SensitiveValueScanner();

    /** 防御性复制问题列表；保留缺省及空条目交给apply返回统一协议异常，不把缺字段视为审阅通过。 */
    public NaturalPromptFidelityReview {
        issues = issues == null ? null : Collections.unmodifiableList(new ArrayList<>(issues));
    }

    /** 只修订精确定位的问题片段；无问题不得借审阅替换已有任务理解或重写正文。 */
    public NaturalPromptDraft apply(NaturalPromptInput input, NaturalPromptDraft draft) {
        if (issues == null || input == null || draft == null) {
            throw invalid("保真审阅记录不完整");
        }
        if (issues.isEmpty()) {
            if (taskUnderstanding != null) throw invalid("无问题审阅不能替换任务理解");
            return draft;
        }
        String body = draft.optimizedPrompt();
        if (issues.size() > MAX_ISSUES || taskUnderstanding == null || body == null || body.isBlank()) {
            throw invalid("保真修订缺少正文、任务记录或超出容量");
        }
        Map<String, NaturalTaskSource> sources = input.taskSources().stream()
                .collect(Collectors.toMap(NaturalTaskSource::id, Function.identity()));
        var patches = new ArrayList<Patch>();
        for (Issue issue : issues) {
            if (issue == null || issue.type() == null) throw invalid("保真问题条目无效");
            checkField(issue.sourceId(), true);
            checkField(issue.sourceQuote(), true);
            checkField(issue.bodyQuote(), true);
            checkField(issue.replacement(), false);
            NaturalTaskSource source = sources.get(issue.sourceId());
            if (source == null || !source.text().contains(issue.sourceQuote())) {
                throw invalid("保真问题引用不属于当前可用来源");
            }
            int start = body.indexOf(issue.bodyQuote());
            if (start < 0 || start != body.lastIndexOf(issue.bodyQuote())) {
                throw invalid("保真问题没有唯一对应的首稿片段");
            }
            patches.add(new Patch(start, start + issue.bodyQuote().length(), issue.replacement()));
        }
        patches.sort(Comparator.comparingInt(Patch::start));
        StringBuilder patched = new StringBuilder(body.length());
        int cursor = 0;
        for (Patch patch : patches) {
            // 所有跨度都来自首稿。拒绝重叠或重复，不让前一个替换生成的文字成为后续删除目标。
            if (patch.start() < cursor) throw invalid("保真问题片段重叠或重复");
            patched.append(body, cursor, patch.start()).append(patch.replacement());
            cursor = patch.end();
        }
        patched.append(body, cursor, body.length());
        String patchedBody = patched.toString();
        // 两个安全片段也可能拼成凭据语境，因此整稿在原最终校验之前再扫描一次。
        if (patchedBody.isBlank() || SENSITIVE.containsCredential(patchedBody)) {
            throw invalid("保真修订正文为空或包含疑似敏感凭据");
        }
        NaturalTaskUnderstanding updated = taskUnderstanding.resolve(input);
        if (updated.review() == null) throw invalid("保真修订缺少正文覆盖记录");
        updated.review().validateEnhancement(updated, patchedBody);
        return new NaturalPromptDraft(patchedBody, draft.framework(), draft.provider(), updated);
    }

    /** 问题类别只限定审阅职责，不按类别自动认定模型判断正确或推断授权。 */
    public enum Type {
        /** 无来源支撑的用户、环境或既有状态断言。 */ UNSUPPORTED_FACT,
        /** 未知、可能或建议被写成已确认事实。 */ STATE_PROMOTION,
        /** 核查或评估被扩大为执行许可。 */ AUTHORITY_EXPANSION,
        /** 原委托范围外增加必做功能或交付。 */ SCOPE_EXPANSION,
        /** 否定、例外或依赖条件被改变。 */ CONDITION_CHANGE,
        /** 原有目标、格式或其他实际要求被遗漏。 */ REQUIREMENT_OMISSION
    }

    /** 来源为当前服务端目录；bodyQuote定位首稿，replacement只替换该片段，允许明确删除。 */
    public record Issue(Type type, String sourceId, String sourceQuote, String bodyQuote, String replacement) { }

    /** 校验审阅片段的协议容量及凭据；仅replacement允许空字符串，不推断该修订在语义上成立。 */
    private static void checkField(String value, boolean required) {
        if (value == null || (required && value.isBlank()) || value.length() > MAX_FIELD_LENGTH
                || SENSITIVE.containsCredential(value)) {
            throw invalid("保真审阅字段无效");
        }
    }

    /** 仅在本次调用内记录首稿UTF-16跨度，不携带新来源或授权。 */
    private record Patch(int start, int end, String replacement) { }

    private static ProviderException invalid(String message) {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, message, false);
    }
}
