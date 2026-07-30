package com.example.clawbot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 定时任务实体 — 持久化到 SQLite。
 */
@Data
@Entity
@Table(name = "scheduled_task")
public class ScheduledTaskEntity {

    @Id
    @Column(length = 36)
    private String taskId;

    @Column(nullable = false)
    private String userId;

    /** 任务类型：remind（一次性提醒）| repeat（周期任务） */
    @Column(nullable = false)
    private String taskType;

    /** 任务描述，如"喝水"、"查微博热搜" */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 延迟秒数（提醒用） */
    private Long delaySeconds;

    /** 间隔秒数（周期任务用） */
    private Long intervalSeconds;

    /** 任务状态：active | completed | cancelled */
    @Column(nullable = false)
    private String status = "active";

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
