package com.finsight.feedback.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class FeedbackStageConverter
        implements AttributeConverter<AiFeedback.FeedbackStage, String> {

    @Override
    public String convertToDatabaseColumn(
            AiFeedback.FeedbackStage attribute
    ) {
        if (attribute == null) {
            return null;
        }

        return attribute.getValue();
    }

    @Override
    public AiFeedback.FeedbackStage convertToEntityAttribute(
            String dbData
    ) {
        if (dbData == null) {
            return null;
        }

        return AiFeedback.FeedbackStage.fromValue(dbData);
    }
}