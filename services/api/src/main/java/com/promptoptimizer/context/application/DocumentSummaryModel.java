package com.promptoptimizer.context.application;

import java.util.List;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 定义文档 Map 与 Reduce 摘要所需的模型调用接口，隔离摘要算法和具体模型协议。
 */
public interface DocumentSummaryModel {

    /** 按 Map 或 Reduce 阶段生成摘要；调用方负责控制批次和输出预算。 */
    SummaryResult summarize(SummaryRequest request);

    enum SummaryStage {
        MAP,
        REDUCE
    }

    record SummaryPart(
            int index,
            String label,
            String content
    ) {

        public SummaryPart {
            label = label == null || label.isBlank() ? "文档正文" : label.trim();
            content = content == null ? "" : content;
        }
    }

    record SummaryRequest(
            SummaryStage stage,
            String path,
            String language,
            List<SummaryPart> parts,
            int maxOutputCharacters
    ) {

        public SummaryRequest {
            if (stage == null) {
                throw new IllegalArgumentException("摘要阶段不能为空");
            }
            if (parts == null || parts.isEmpty()) {
                throw new IllegalArgumentException("摘要内容不能为空");
            }
            if (maxOutputCharacters < 1) {
                throw new IllegalArgumentException("摘要输出字符上限必须大于 0");
            }
            path = path == null ? "" : path;
            language = language == null ? "text" : language;
            parts = List.copyOf(parts);
        }
    }

    record SummaryResult(
            String summary,
            String model
    ) {

        public SummaryResult {
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("模型摘要不能为空");
            }
            summary = summary.trim();
            model = model == null ? "" : model.trim();
        }
    }
}
