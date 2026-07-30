package com.example.clawbot.knowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetriever {

    private final KnowledgeService knowledgeService;

    @Value("${rag.top-k:5}")
    private int topK;

    @Value("${rag.similarity-threshold:0.7}")
    private double similarityThreshold;

    /**
     * 从知识库检索与查询相关的文档片段，拼接为上下文文本
     */
    public String retrieve(String query) {
        List<Map<String, Object>> results = knowledgeService.search(query, topK);

        if (results.isEmpty()) {
            return "";
        }

        // 过滤低相似度结果（score 越低越相似，SimpleVectorStore 返回的是距离）
        // 这里不做阈值过滤，因为不同 embedding 模型的 score 范围不同
        // 由调用方判断是否使用检索结果

        String context = results.stream()
                .map(r -> {
                    String title = r.get("title") != null ? r.get("title").toString() : "未知";
                    String content = r.get("content").toString();
                    return "【" + title + "】" + content;
                })
                .collect(Collectors.joining("\n---\n"));

        log.info("RAG 检索完成: query='{}', 结果数={}", query, results.size());
        return context;
    }
}
