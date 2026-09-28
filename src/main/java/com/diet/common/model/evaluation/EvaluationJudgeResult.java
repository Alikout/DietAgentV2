package com.diet.common.model.evaluation;

public record EvaluationJudgeResult(
        double explanationQuality,
        double naturalness,
        String reason
) {
}