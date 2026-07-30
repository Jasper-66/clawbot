package com.example.clawbot.knowledge.repository;

import com.example.clawbot.knowledge.model.KnowledgeDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class KnowledgeDocumentRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public void createTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS knowledge_documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                source TEXT,
                category TEXT DEFAULT 'default',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
    }

    private final RowMapper<KnowledgeDocument> rowMapper = (rs, rowNum) -> KnowledgeDocument.builder()
            .id(rs.getLong("id"))
            .title(rs.getString("title"))
            .content(rs.getString("content"))
            .source(rs.getString("source"))
            .category(rs.getString("category"))
            .createdAt(rs.getString("created_at"))
            .updatedAt(rs.getString("updated_at"))
            .build();

    public KnowledgeDocument save(KnowledgeDocument doc) {
        String now = OffsetDateTime.now(ZONE).toString();
        if (doc.getId() == null) {
            jdbcTemplate.update(
                    "INSERT INTO knowledge_documents (title, content, source, category, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    doc.getTitle(), doc.getContent(), doc.getSource(),
                    doc.getCategory() != null ? doc.getCategory() : "default",
                    now, now
            );
            Long id = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
            doc.setId(id);
            doc.setCreatedAt(now);
            doc.setUpdatedAt(now);
        } else {
            jdbcTemplate.update(
                    "UPDATE knowledge_documents SET title=?, content=?, source=?, category=?, updated_at=? WHERE id=?",
                    doc.getTitle(), doc.getContent(), doc.getSource(),
                    doc.getCategory() != null ? doc.getCategory() : "default",
                    now, doc.getId()
            );
            doc.setUpdatedAt(now);
        }
        return doc;
    }

    public List<KnowledgeDocument> findAll() {
        return jdbcTemplate.query("SELECT * FROM knowledge_documents ORDER BY updated_at DESC", rowMapper);
    }

    public List<KnowledgeDocument> findByCategory(String category) {
        return jdbcTemplate.query("SELECT * FROM knowledge_documents WHERE category = ? ORDER BY updated_at DESC", rowMapper, category);
    }

    public KnowledgeDocument findById(Long id) {
        List<KnowledgeDocument> results = jdbcTemplate.query("SELECT * FROM knowledge_documents WHERE id = ?", rowMapper, id);
        return results.isEmpty() ? null : results.get(0);
    }

    public void deleteById(Long id) {
        jdbcTemplate.update("DELETE FROM knowledge_documents WHERE id = ?", id);
    }
}
