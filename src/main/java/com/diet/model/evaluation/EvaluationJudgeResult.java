package com.diet.model.evaluation;

public record EvaluationJudgeResult(
        double explanationQuality,
        double naturalness,
        String reason
) {
}