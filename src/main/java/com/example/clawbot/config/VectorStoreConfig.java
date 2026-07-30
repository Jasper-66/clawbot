package com.example.clawbot.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(
            EmbeddingModel embeddingModel,
            @Value("${rag.vectors-file:knowledge-vectors.json}") String vectorsFile) {

        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel)
                .build();

        // 从文件加载已有向量数据
        File file = new File(vectorsFile);
        if (file.exists()) {
            store.load(file);
        }

        return store;
    }
}
