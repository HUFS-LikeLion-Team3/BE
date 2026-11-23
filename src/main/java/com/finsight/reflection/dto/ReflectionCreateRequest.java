package com.finsight.reflection.dto;

import java.util.List;
import java.util.UUID;

// The only accepted JSON fields are those defined by the reflection POST API.
public record ReflectionCreateRequest(
        UUID clientRequestId,
        UUID sessionTargetId,
        String reflectionType,
        String promptKey,
        String body,
        List<Answer> answers
) {
    public record Answer(String promptKey, String body) { }
}
