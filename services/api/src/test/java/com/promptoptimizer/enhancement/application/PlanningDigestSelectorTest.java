package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.context.domain.FileSnippet;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

class PlanningDigestSelectorTest {
    @Test
    void shouldIncludeRelevantFileBeyondOriginalCutoffAndKeepOtherDirectories() {
        var files = new ArrayList<>(IntStream.range(0, 60).mapToObj(i ->
                new FileSnippet("src/generated/" + i + ".java", "java", "", "普通工具", false)).toList());
        files.add(new FileSnippet("research/研究方案.md", "md", "", "YLL参考寿命表和研究范围", false));
        files.add(new FileSnippet("docs/readme.md", "md", "", "系统说明", false));
        var selected = PlanningDigestSelector.select(files, "YLL参考寿命表", 30);
        assertThat(selected).hasSize(30);
        assertThat(selected).extracting(FileSnippet::path).contains("research/研究方案.md", "docs/readme.md");
    }

    @Test
    void shouldReserveDigestSpaceForAnUploadedSolutionDocumentWhenCodeMatchesQueryBetter() {
        var files = new ArrayList<>(IntStream.range(0, 40).mapToObj(i ->
                new FileSnippet("module-" + i + "/OrderApproval.java", "java", "",
                        "订单审批代码", false)).toList());
        files.add(new FileSnippet("attachments/solution.txt", "text", "",
                "业务方案，含金额阈值和财务复核规则", false));

        var selected = PlanningDigestSelector.select(files, "实现订单审批", 30);

        assertThat(selected).hasSize(30);
        assertThat(selected).extracting(FileSnippet::path).contains("attachments/solution.txt");
    }
}
