package com.finsight.prediction.dto;

import java.util.List;
import java.util.Map;

/**
 * Map preserves absent vs explicitly-null fields in a PATCH-like PUT:
 * absent -> retain saved value, null -> clear saved value.
 */
public record PredictionUpdateRequest(List<Map<String, Object>> predictions) {
}
