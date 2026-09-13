package com.promptoptimizer.context.infrastructure.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.promptoptimizer.context.application.TextEmbeddingModel;
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
            return mapResponse(response, inputs.size());
        } catch (RestClientResponseException exception) {
            throw new EmbeddingProviderException(
                    "向量模型拒绝请求，HTTP " + exception.getStatusCode().value(),
                    exception
            );
        } catch (ResourceAccessException exception) {
            throw new EmbeddingProviderException("无法连接向量模型或请求超时", exception);
        } catch (RestClientException exception) {
            throw new EmbeddingProviderException("向量模型暂时不可用", exception);
        }
    }

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
    private record EmbeddingResponse(String model, List<EmbeddingData> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingData(int index, List<Double> embedding) {
    }
}
