package com.example.clawbot.knowledge.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class
KnowledgeDocument {
    private Long id;
    private String title;
    private String content;
    private String source;
    private String category;
    private String createdAt;
    private String updatedAt;
}
