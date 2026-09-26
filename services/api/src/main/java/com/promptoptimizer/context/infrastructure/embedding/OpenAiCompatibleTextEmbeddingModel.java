package com.promptoptimizer.context.infrastructure.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.promptoptimizer.common.logging.ModelCallLogger;
import com.promptoptimizer.context.service.TextEmbeddingModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 使用 OpenAI Embeddings 兼容协议调用本地或远程文本向量模型。
 */
public class OpenAiCompatibleTextEmbeddingModel implements TextEmbeddingModel {

    private final RestClient restClient;
    private final SemanticRetrievalProperties properties;

    public OpenAiCompatibleTextEmbeddingModel(
            RestClient restClient,
            SemanticRetrievalProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * 发送批量向量请求，并严格校验响应数量、顺序与数值，防止损坏索引。
     */
    @Override
    public EmbeddingBatch embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("向量输入不能为空");
        }
        long startedAt = System.nanoTime();
        String modelId = properties.getModel();
        int inputItems = inputs.size();
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(properties.getEndpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);
            if (!properties.getApiKey().isBlank()) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey());
            }
            EmbeddingResponse response = request
                    .body(new EmbeddingRequest(properties.getModel(), List.copyOf(inputs)))
                    .retrieve()
                    .body(EmbeddingResponse.class);
            EmbeddingBatch batch = mapResponse(response, inputItems);
            ModelCallLogger.completed("context.embedding", "semantic-retrieval", modelId,
                    "SERVER_CONFIGURED", false, 1, inputItems, elapsedMillis(startedAt),
                    toTokenUsage(response.usage()));
            return batch;
        } catch (EmbeddingProviderException exception) {
            ModelCallLogger.failed("context.embedding", "semantic-retrieval", modelId, "SERVER_CONFIGURED",
                    "INVALID_RESPONSE", false, null, false, 1, inputItems, elapsedMillis(startedAt));
            throw exception;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            String failureType = failureType(status);
            ModelCallLogger.failed("context.embedding", "semantic-retrieval", modelId, "SERVER_CONFIGURED",
                    failureType, retryable(status), status, false, 1, inputItems, elapsedMillis(startedAt));
            throw new EmbeddingProviderException(
                    "向量模型拒绝请求，HTTP " + status,
                    exception
            );
        } catch (ResourceAccessException exception) {
            ModelCallLogger.failed("context.embedding", "semantic-retrieval", modelId, "SERVER_CONFIGURED",
                    "RESOURCE_ACCESS", true, null, false, 1, inputItems, elapsedMillis(startedAt));
            throw new EmbeddingProviderException("无法连接向量模型或请求超时", exception);
        } catch (RestClientException exception) {
            ModelCallLogger.failed("context.embedding", "semantic-retrieval", modelId, "SERVER_CONFIGURED",
                    "UPSTREAM_UNAVAILABLE", true, null, false, 1, inputItems, elapsedMillis(startedAt));
            throw new EmbeddingProviderException("向量模型暂时不可用", exception);
        } catch (RuntimeException exception) {
            ModelCallLogger.failed("context.embedding", "semantic-retrieval", modelId, "SERVER_CONFIGURED",
                    "UNEXPECTED", false, null, false, 1, inputItems, elapsedMillis(startedAt));
            throw exception;
        }
    }

    /** 把上游 HTTP 状态折叠为稳定类别，不记录供应商返回的错误正文。 */
    private String failureType(int status) {
        if (status == 401 || status == 403) return "AUTHENTICATION";
        if (status == 429) return "RATE_LIMIT";
        if (status == 408 || status == 504) return "TIMEOUT";
        if (status >= 500) return "UPSTREAM_UNAVAILABLE";
        return "REQUEST_REJECTED";
    }

    /** 标识故障是否具有短暂性，不代表向量适配器会自动重试。 */
    private boolean retryable(int status) {
        return status == 408 || status == 429 || status == 504 || status >= 500;
    }

    /** 将单次上游调用耗时转换为毫秒。 */
    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /** 仅使用上游明确返回的向量请求用量，不对文本长度估算 token。 */
    private ModelCallLogger.TokenUsage toTokenUsage(EmbeddingUsage usage) {
        return usage == null ? null : new ModelCallLogger.TokenUsage(
                usage.promptTokens(), null, usage.totalTokens());
    }

    /** 按上游索引还原输入顺序，并拒绝缺项、重复索引及非有限向量值。 */
    private EmbeddingBatch mapResponse(EmbeddingResponse response, int expectedSize) {
        if (response == null || response.data() == null || response.data().size() != expectedSize) {
            throw new EmbeddingProviderException("向量模型响应数量与请求不一致");
        }
        if (response.data().stream().anyMatch(item -> item == null)) {
            throw new EmbeddingProviderException("向量模型响应包含空条目");
        }
        List<EmbeddingData> ordered = response.data().stream()
                .sorted(Comparator.comparingInt(EmbeddingData::index))
                .toList();
        List<float[]> vectors = new ArrayList<>(expectedSize);
        for (int expectedIndex = 0; expectedIndex < ordered.size(); expectedIndex++) {
            EmbeddingData item = ordered.get(expectedIndex);
            if (item.index() != expectedIndex || item.embedding() == null || item.embedding().isEmpty()) {
                throw new EmbeddingProviderException("向量模型响应索引或向量内容无效");
            }
            float[] vector = new float[item.embedding().size()];
            for (int dimension = 0; dimension < item.embedding().size(); dimension++) {
                Double value = item.embedding().get(dimension);
                if (value == null || !Double.isFinite(value)) {
                    throw new EmbeddingProviderException("向量模型响应包含无效数值");
                }
                float converted = value.floatValue();
                if (!Float.isFinite(converted)) {
                    throw new EmbeddingProviderException("向量模型响应数值超出可用范围");
                }
                vector[dimension] = converted;
            }
            vectors.add(vector);
        }
        String model = response.model() == null || response.model().isBlank()
                ? properties.getModel()
                : response.model();
        return new EmbeddingBatch(model, vectors);
    }

    private record EmbeddingRequest(String model, List<String> input) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingResponse(String model, List<EmbeddingData> data, EmbeddingUsage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingUsage(
            @com.fasterxml.jackson.annotation.JsonProperty("prompt_tokens") Long promptTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("total_tokens") Long totalTokens
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingData(int index, List<Double> embedding) {
    }
}
