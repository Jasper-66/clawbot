package com.example.clawbot.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 通用定时任务实体。
 *
 * <p>支持多种任务类型：提醒、延迟图片生成、延迟天气查询等。
 * 由 ScheduledTaskScheduler 每分钟扫描并执行到期任务。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "scheduled_tasks", indexes = {
        @Index(name = "idx_st_user", columnList = "user_id"),
        @Index(name = "idx_st_status", columnList = "status"),
        @Index(name = "idx_st_time", columnList = "execute_at")
})
public class ScheduledTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户ID */
    @Column(name = "user_id", nullable = false)
    private String userId;

    /** 任务类型: REMIND / IMAGE / WEATHER / CUSTOM */
    @Column(name = "task_type", nullable = false, length = 32)
    private String taskType;

    /** 任务内容（提醒文本 / 图片prompt / 天气城市 / 自定义消息） */
    @Column(name = "content", nullable = false, length = 1000)
    private String content;

    /** 执行时间 */
    @Column(name = "execute_at", nullable = false)
    private LocalDateTime executeAt;

    /** 重复类型: NONE / DAILY / WEEKLY / MONTHLY */
    @Column(name = "repeat_type", nullable = false, length = 16)
    @Builder.Default
    private String repeatType = "NONE";

    /** 状态: ACTIVE / COMPLETED / CANCELLED */
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "ACTIVE";
        if (repeatType == null) repeatType = "NONE";
    }
}
