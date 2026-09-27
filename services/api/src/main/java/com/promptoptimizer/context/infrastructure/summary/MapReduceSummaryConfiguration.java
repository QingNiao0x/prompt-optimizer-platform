package com.promptoptimizer.context.infrastructure.summary;

import com.promptoptimizer.context.service.DocumentSummaryModel;
import com.promptoptimizer.context.service.impl.FileContentSummarizer;
import com.promptoptimizer.context.service.impl.MapReduceDocumentSummarizer;
import com.promptoptimizer.context.service.MapReduceSummaryOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 装配 Map-Reduce 摘要模块，并允许无模型环境安全退回本地规则摘要。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MapReduceSummaryProperties.class)
public class MapReduceSummaryConfiguration {

    @Bean
    MapReduceDocumentSummarizer mapReduceDocumentSummarizer(
            FileContentSummarizer localSummarizer,
            ObjectProvider<DocumentSummaryModel> summaryModels,
            MapReduceSummaryProperties properties
    ) {
        Optional<DocumentSummaryModel> summaryModel = summaryModels.orderedStream().findFirst();
        return new MapReduceDocumentSummarizer(
                localSummarizer,
                summaryModel,
                new MapReduceSummaryOptions(
                        properties.isEnabled(),
                        properties.getMapBatchSize(),
                        properties.getReduceBatchSize(),
                        properties.getMaxBatchCharacters(),
                        properties.getIntermediateSummaryCharacters(),
                        properties.getFinalSummaryCharacters(),
                        properties.getMaxMapCalls(),
                        properties.getMaxReduceCalls()
                )
        );
    }
}
