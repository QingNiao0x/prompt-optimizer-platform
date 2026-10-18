import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.service.impl.*;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.nio.file.*;
import java.util.*;

/**
 * 对冻结父/新运行回放任务身份、适用平台规则及包装问题；无模型、数据库或账号操作。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class Candidate40IntentReplay {
    /** 运行文件和输出目录均显式绑定，结果独占创建，不覆盖旧回放。 */
    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path out = root.resolve(args[0]).normalize();
        Path runtime = Path.of(TaskIntentResolver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!out.startsWith(root.resolve("tmp/me01-candidate40-20261018")) || !runtime.equals(root.resolve(args[1]).normalize()))
            throw new IllegalArgumentException("Unexpected replay scope");
        var mapper = new ObjectMapper();
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
        var examples = List.of("用TypeScript实现文本去重。", "使用Rust实现文件压缩。", "使用C++实现整数排序。",
                "用TypeScript实现文本去重。交付方式由我决定，请先询问我是要脚本还是函数。",
                "解释如何用TypeScript实现文本去重。", "用TypeScript实现文本去重。不交付代码。",
                "用TypeScript实现文本去重，不附带示例代码。", "使用requests库爬取网页标题，不附带示例代码。",
                "未来任务：\n用TypeScript实现文本去重。", "如果以后确认需要，用TypeScript实现文本去重。",
                "用R实现统计分析。", "使用Excel实现文本去重。");
        var packaging = new PlanQuestion("packaging", "代码采用哪种交付形式？", "选择脚本、函数或模块。", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("script", "可直接运行的脚本", "单个脚本", "交付可直接运行的脚本", false, ""),
                        new PlanOption("module", "可复用函数或模块", "可被其他代码调用的函数", "交付可复用函数或模块", false, "")), List.of(), true);
        var rows = new ArrayList<Map<String, Object>>();
        for (String raw : examples) {
            var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
            var view = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), false);
            var constraints = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), true, intent);
            if (!view.intent().equals(intent) || new PromptTemplateRegistryImpl().infer(raw) != intent.templateCode())
                throw new IllegalStateException("Shared identity diverged");
            rows.add(Map.of("rawPrompt", raw, "intent", intent, "sharedIdentityMatches", true,
                    "platformMandatory", constraints.platformMandatory(), "packagingQuestionRetained",
                    !new PlanQuestionFilter().filter(List.of(packaging), new PlanningProviderRequest(raw, "", List.of())).isEmpty()));
        }
        Files.createDirectories(out);
        Files.writeString(out.resolve("intent-replay.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("runtime", runtime.toString(), "observations", rows, "realModelCalls", 0)), StandardOpenOption.CREATE_NEW);
        System.out.println("Offline intent replay entries=" + rows.size() + "; realModelCalls=0");
    }
}
