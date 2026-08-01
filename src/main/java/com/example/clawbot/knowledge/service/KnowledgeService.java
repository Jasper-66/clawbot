package com.example.clawbot.knowledge.service;

import com.example.clawbot.knowledge.model.KnowledgeDocument;
import com.example.clawbot.knowledge.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final VectorStore vectorStore;
    private final KnowledgeDocumentRepository documentRepository;
    private final Tika tika = new Tika();

    @Value("${rag.vectors-file:knowledge-vectors.json}")
    private String vectorsFile;

    @Value("${rag.top-k:5}")
    private int topK;

    /**
     * 添加文本文档到知识库
     */
    public KnowledgeDocument addDocument(String title, String content, String source, String category) {
        // 1. 保存元数据到 SQLite
        KnowledgeDocument doc = KnowledgeDocument.builder()
                .title(title)
                .content(content)
                .source(source)
                .category(category)
                .build();
        doc = documentRepository.save(doc);

        // 2. 文本分片
        TokenTextSplitter splitter = new TokenTextSplitter(200, 50, 10, 10000, true, List.of());
        List<Document> documents = splitter.split(new Document(content, Map.of(
                "docId", doc.getId().toString(),
                "title", title,
                "category", category != null ? category : "default"
        )));

        // 3. 存入向量库
        vectorStore.add(documents);
        saveVectorStore();

        log.info("知识库文档已添加: id={}, title={}, 片段数={}", doc.getId(), title, documents.size());
        return doc;
    }

    /**
     * 上传文件到知识库
     */
    public KnowledgeDocument uploadFile(MultipartFile file, String category) throws IOException {
        String text;
        try {
            text = tika.parseToString(file.getInputStream());
        } catch (Exception e) {
            throw new IOException("文档解析失败: " + e.getMessage(), e);
        }
        String title = file.getOriginalFilename() != null ? file.getOriginalFilename() : "未命名文档";
        return addDocument(title, text, "file:" + title, category);
    }

    /**
     * 删除文档
     */
    public void deleteDocument(Long id) {
        documentRepository.deleteById(id);
        // 向量库中的片段通过 docId metadata 关联，SimpleVectorStore 不支持按 metadata 删除
        // 需要重建向量库（对小规模数据可行）
        rebuildVectorStore();
        log.info("知识库文档已删除: id={}", id);
    }

    /**
     * 知识检索
     */
    public List<Map<String, Object>> search(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK > 0 ? topK : this.topK)
                .build();

        List<Document> results = vectorStore.similaritySearch(request);

        return results.stream().map(doc -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("content", doc.getText());
            item.put("score", doc.getScore());
            item.put("title", doc.getMetadata().get("title"));
            item.put("docId", doc.getMetadata().get("docId"));
            return item;
        }).collect(Collectors.toList());
    }

    /**
     * 获取所有文档
     */
    public List<KnowledgeDocument> listDocuments(String category) {
        if (category != null && !category.isEmpty()) {
            return documentRepository.findByCategory(category);
        }
        return documentRepository.findAll();
    }

    /**
     * 获取文档详情
     */
    public KnowledgeDocument getDocument(Long id) {
        return documentRepository.findById(id);
    }

    /**
     * 持久化向量数据到文件
     */
    private void saveVectorStore() {
        try {
            ((org.springframework.ai.vectorstore.SimpleVectorStore) vectorStore)
                    .save(new File(vectorsFile));
        } catch (Exception e) {
            log.warn("保存向量数据失败: {}", e.getMessage());
        }
    }

    /**
     * 重建向量库（删除文档后）
     */
    private void rebuildVectorStore() {
        List<KnowledgeDocument> allDocs = documentRepository.findAll();
        // 清空向量库需要重新初始化，这里简单记录日志
        log.info("向量库重建: 当前文档数={}, 建议重启应用同步", allDocs.size());
        // 对于 SimpleVectorStore，删除操作需要重建
        // 在生产环境中应使用支持删除的向量数据库
    }
}
