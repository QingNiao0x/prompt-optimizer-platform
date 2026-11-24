package com.promptoptimizer.provider.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 18 题只检查回归口径。这些正文是对照样例，不是本轮模型采样。
 */
class CurrentTaskPromptRegressionTest {
    private static final String RAW = """
            我需要做一个常备流调队功能模块, 代码放在E:\\CompanyProject\\中科软\\二期项目\\智能流调\\btew-iefs-vue3\\src\\views\\ieis\\epidemiological-investigation-team\\epidemic-investigation目录中, 其中里面的字段一致, 只有主表中的类型为队员类型(兵团级、师市级), 活动类型子表同专家流调队的子表以及字段一致只是常备流调队的活动类型子表没有对口指导子表, 流调专家队和将要新增的常备流调队的字段以及子表都差不多, 请先进行评估是否可以使用同一张数据库表请先给出结论先不要修改代码""";
    private static final String MAPPING = "专家流调队人员主表为 ieis_expert_member。活动记录表为 ieis_expert_activity，活动类型是字段而不是物理子表。";
    private static final Pattern IDENTIFIER = Pattern.compile("ieis_[A-Za-z0-9_]+|ExpertActivityConst\\.[A-Za-z0-9_]+|TYPE_GUIDE");

    @Test
    void previousOptimizerTextBorrowsUnstatedTables() {
        String previous = """
                请先评估常备流调队是否可以使用同一张数据库表，先给出结论，先不要修改代码。评估时请对照现有流调专家队的主表与活动类型子表结构（如 ieis_investigation_team、ieis_expert_activity 及其关联子表）。主表中的类型字段为队员类型（兵团级、师市级），活动类型子表没有对口指导子表。""";
        assertThat(issues(RAW, "", previous)).contains("ieis_investigation_team", "ieis_expert_activity");
    }

    @Test
    void withoutSchemaTheTargetKeepsDifferencesAndAvoidsDesignConclusions() {
        String target = """
                本轮只评估常备流调队能否与专家流调队共用数据库表，先查证后再给出结论，不修改代码。后续新增模块不是这轮授权。
                代码目录在 E:\\CompanyProject\\中科软\\二期项目\\智能流调\\btew-iefs-vue3\\src\\views\\ieis\\epidemiological-investigation-team\\epidemic-investigation。
                已知差异是：常备队主表中的类型表示队员类型（兵团级、师市级）；活动侧与专家流调队大体一致，但常备队没有对口指导。
                分别从该目录核对人员主表和活动记录：一行代表什么、类型字段的业务含义能否表达队员类型、子记录如何关联。
                用户说的子表要核实是界面中的子项、业务活动类型，还是独立物理表。
                现有材料没有表名。复用现有字段还是新增字段留给核对结果，不提前确定。
                若共表，说明对现有查询、查重和权限的影响；规则未确认时，分别说明不同取值会怎样改变结论。""";
        assertThat(issues(RAW, "", target)).isEmpty();
    }

    @Test
    void accurateMappingCanBeUsedWithoutBorrowingAnotherTable() {
        String target = """
                本轮只评估能否共用数据库表，先查证后再给出结论，不修改代码。目录仍是 epidemic-investigation。
                资料写明人员主表是 ieis_expert_member，活动记录表是 ieis_expert_activity，活动类型是字段而不是物理子表。
                常备队主表中的类型表示队员类型（兵团级、师市级），并且没有对口指导。
                沿这两张已给出的表核对一行代表什么、类型字段能否表达队员类型、活动记录如何关联，以及共表对查询、查重和权限的影响。
                复用现有字段还是新增字段留给核对结果，不提前确定。未确认的业务规则要说明不同取值如何改变结论。""";
        assertThat(issues(RAW, MAPPING, target)).isEmpty();
        assertThat(issues(RAW, "", target)).contains("ieis_expert_member");
    }

    @Test
    void anotherImplementationTaskDoesNotGainTheTableChecklistOrANarrowerAuthorization() {
        String raw = "登录接口出现空指针。请直接修改现有登录代码并补回归测试，不要改权限模型。";
        String mechanical = "先不要修改代码。先评估这些表能否共用数据库表，核对查重和权限，并确定必须新增字段。";
        String focused = "直接修改现有登录代码中的空指针，并补回归测试。不要改权限模型。";
        assertThat(issues(raw, "", mechanical)).isNotEmpty();
        assertThat(issues(raw, "", focused)).isEmpty();
    }

    /** 只标出回归要拦住的越权和无依据名称，不判断整篇是否写得好。 */
    private static List<String> issues(String raw, String context, String body) {
        String sources = raw + "\n" + context;
        List<String> found = new ArrayList<>();
        Matcher names = IDENTIFIER.matcher(body);
        while (names.find()) {
            if (!sources.contains(names.group())) found.add(names.group());
        }
        if (body.contains("必须新增") && !sources.contains("必须新增")) found.add("必须新增");
        if (body.contains("新增一个") && !sources.contains("新增一个")) found.add("新增一个");
        if (body.contains("共用数据库表") && !sources.contains("共用") && !sources.contains("同一张数据库表")) found.add("共用数据库表");
        boolean readOnly = sources.contains("不要修改代码") || sources.contains("不修改代码") || sources.contains("先不要修改");
        boolean implementNow = sources.contains("直接修改") && !readOnly;
        if (readOnly && !body.contains("不修改代码") && !body.contains("不要修改代码")) found.add("丢失只读边界");
        if (implementNow && (body.contains("不要修改代码") || body.contains("不修改代码"))) found.add("把实施收成只读");
        if (sources.contains("队员类型") && !body.contains("队员类型")) found.add("丢失队员类型");
        if (sources.contains("兵团级") && !body.contains("兵团级")) found.add("丢失兵团级");
        if (sources.contains("对口指导") && !body.contains("对口指导")) found.add("丢失对口指导");
        if (sources.contains("同一张数据库表") && context.isBlank() && !body.contains("主表")) found.add("没有分别提示主表");
        return found;
    }
}
