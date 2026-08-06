package com.example.clawbot.liepin.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiepinApplyResult {
    private boolean success;
    private String message;
    private String applicationId;
}
