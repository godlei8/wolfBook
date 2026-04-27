package com.wolfbook.backend.ai.model;

public record AiRetrievalCandidate(
        String chunkUid,
        String subject,
        String content,
        double score,
        String source
) {
    public AiRetrievalCandidate withScoreAndSource(double nextScore, String nextSource) {
        return new AiRetrievalCandidate(chunkUid, subject, content, nextScore, nextSource);
    }
}
