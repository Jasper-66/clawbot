package com.example.clawbot.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 消息日志实体
 *
 * <p>对应 SQLite 中的 message_log 表，由 Hibernate 自动建表（ddl-auto=update）。
 * 字段说明：</p>
 * <ul>
 *   <li>id — 自增主键</li>
 *   <li>userId — 发送者微信用户ID</li>
 *   <li>content — 消息内容，最长 2000 字符</li>
 *   <li>msgType — 消息类型：text / image / voice / file</li>
 *   <li>direction — 消息方向：in（收到）/ out（发出）</li>
 *   <li>createTime — 创建时间，插入时自动填充</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "message_log", indexes = {
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_msg_type", columnList = "msg_type"),
        @Index(name = "idx_create_time", columnList = "create_time")
})
public class MessageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发送者微信用户ID */
    @Column(name = "user_id", nullable = false)
    private String userId;

    /** 消息内容 */
    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    /** 消息类型: text / image / voice / file */
    @Column(name = "msg_type", length = 32)
    private String msgType;

    /** 消息方向: in=收到, out=发出 */
    @Column(name = "direction", length = 8)
    @Builder.Default
    private String direction = "in";

    /** 创建时间 */
    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    public void prePersist() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
    }
}
