package com.promptoptimizer.context.service;

import java.util.ArrayList;
import java.util.List;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 定义文本向量模型的调用接口，使语义索引不依赖具体模型厂商协议。
 */
public interface TextEmbeddingModel {

    /**
     * 将一批文本转换为向量。返回顺序必须与输入顺序保持一致。
     */
    EmbeddingBatch embed(List<String> inputs);

    /**
     * 向量模型的一次批量响应。
     */
    record EmbeddingBatch(String model, List<float[]> vectors) {

        public EmbeddingBatch {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("向量模型名称不能为空");
            }
            if (vectors == null) {
                throw new IllegalArgumentException("向量结果不能为空");
            }
            List<float[]> copiedVectors = new ArrayList<>(vectors.size());
            for (float[] vector : vectors) {
                if (vector == null) {
                    throw new IllegalArgumentException("向量结果不能包含空值");
                }
                copiedVectors.add(vector.clone());
            }
            vectors = List.copyOf(copiedVectors);
        }
    }
}
