package com.example.clawbot.knowledge.controller;

import com.example.clawbot.exception.Result;
import com.example.clawbot.knowledge.model.KnowledgeDocument;
import com.example.clawbot.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    /**
     * 添加文本文档
     */
    @PostMapping("/documents")
    public Result<KnowledgeDocument> addDocument(@RequestBody Map<String, String> body) {
        String title = body.get("title");
        String content = body.get("content");
        String source = body.get("source");
        String category = body.get("category");

        if (title == null || title.isBlank()) {
            return Result.error("标题不能为空");
        }
        if (content == null || content.isBlank()) {
            return Result.error("内容不能为空");
        }

        KnowledgeDocument doc = knowledgeService.addDocument(title, content, source, category);
        return Result.success(doc);
    }

    /**
     * 上传文件
     */
    @PostMapping("/upload")
    public Result<KnowledgeDocument> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", required = false, defaultValue = "default") String category) {
        if (file.isEmpty()) {
            return Result.error("文件不能为空");
        }
        try {
            KnowledgeDocument doc = knowledgeService.uploadFile(file, category);
            return Result.success(doc);
        } catch (Exception e) {
            log.error("文件解析失败", e);
            return Result.error("文件解析失败: " + e.getMessage());
        }
    }

    /**
     * 文档列表
     */
    @GetMapping("/documents")
    public Result<List<KnowledgeDocument>> listDocuments(
            @RequestParam(value = "category", required = false) String category) {
        return Result.success(knowledgeService.listDocuments(category));
    }

    /**
     * 文档详情
     */
    @GetMapping("/documents/{id}")
    public Result<KnowledgeDocument> getDocument(@PathVariable Long id) {
        KnowledgeDocument doc = knowledgeService.getDocument(id);
        if (doc == null) {
            return Result.error("文档不存在");
        }
        return Result.success(doc);
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/documents/{id}")
    public Result<Void> deleteDocument(@PathVariable Long id) {
        knowledgeService.deleteDocument(id);
        return Result.success(null);
    }

    /**
     * 知识检索测试
     */
    @PostMapping("/search")
    public Result<List<Map<String, Object>>> search(@RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        Integer topK = body.containsKey("topK") ? ((Number) body.get("topK")).intValue() : 5;

        if (query == null || query.isBlank()) {
            return Result.error("查询内容不能为空");
        }

        return Result.success(knowledgeService.search(query, topK));
    }
}
