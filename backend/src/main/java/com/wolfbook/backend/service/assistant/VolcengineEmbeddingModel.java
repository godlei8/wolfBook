package com.wolfbook.backend.service.assistant;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class VolcengineEmbeddingModel extends AbstractEmbeddingModel {

    private final VolcengineEmbeddingService volcengineEmbeddingService;

    public VolcengineEmbeddingModel(VolcengineEmbeddingService volcengineEmbeddingService) {
        this.volcengineEmbeddingService = volcengineEmbeddingService;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request == null || request.getInstructions() == null ? List.of() : request.getInstructions();
        List<float[]> vectors = toFloatVectors(volcengineEmbeddingService.embedBatch(instructions));
        List<Embedding> results = new ArrayList<>();
        for (int index = 0; index < vectors.size(); index++) {
            float[] vector = vectors.get(index);
            if (this.embeddingDimensions.get() <= 0 && vector.length > 0) {
                this.embeddingDimensions.compareAndSet(0, vector.length);
            }
            results.add(new Embedding(vector, index));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(Document document) {
        if (document == null || document.getText() == null) {
            return new float[0];
        }
        float[] vector = toFloatVector(volcengineEmbeddingService.embed(document.getText()));
        if (this.embeddingDimensions.get() <= 0 && vector.length > 0) {
            this.embeddingDimensions.compareAndSet(0, vector.length);
        }
        return vector;
    }

    @Override
    public int dimensions() {
        int value = super.dimensions();
        if (value > 0) {
            return value;
        }
        return volcengineEmbeddingService.defaultDimensions();
    }

    private List<float[]> toFloatVectors(List<List<Double>> vectors) {
        List<float[]> results = new ArrayList<>();
        for (List<Double> vector : vectors) {
            results.add(toFloatVector(vector));
        }
        return results;
    }

    private float[] toFloatVector(List<Double> vector) {
        if (vector == null || vector.isEmpty()) {
            return new float[0];
        }
        float[] values = new float[vector.size()];
        for (int index = 0; index < vector.size(); index++) {
            values[index] = vector.get(index).floatValue();
        }
        return values;
    }
}
