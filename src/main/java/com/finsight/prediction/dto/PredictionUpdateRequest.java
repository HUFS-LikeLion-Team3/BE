package com.finsight.prediction.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

public record PredictionUpdateRequest(
        List<PredictionRequest> predictions
) {

    @Getter
    @NoArgsConstructor
    public static class PredictionRequest {

        private Integer horizon;

        private String direction;

        private String reason;

        private boolean directionProvided;

        private boolean reasonProvided;

        @JsonSetter("horizon")
        public void setHorizon(Integer horizon) {
            this.horizon = horizon;
        }

        @JsonSetter("direction")
        public void setDirection(String direction) {
            this.direction = direction;
            this.directionProvided = true;
        }

        @JsonSetter("reason")
        public void setReason(String reason) {
            this.reason = reason;
            this.reasonProvided = true;
        }
    }
}
