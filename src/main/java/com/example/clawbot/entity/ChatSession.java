package com.example.clawbot.entity;

import com.github.wechat.ilink.sdk.ILinkClient;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信聊天会话 — 每个登录用户对应一个独立会话。
 *
 * <p>每个会话拥有独立的 {@link ILinkClient} 实例、消息轮询线程和去重集合，
 * 多个会话之间互不影响。</p>
 */
@Data
public class ChatSession {

    /** 会话唯一标识（UUID） */
    private String sessionId;

    /** ILink 微信客户端实例（每个会话独立） */
    private ILinkClient client;

    /** 登录后的微信 bot ID */
    private String botId;

    /** 轮询运行标志，设为 false 以停止该会话的轮询 */
    private volatile boolean running;

    /** 已处理消息 ID 集合（线程安全），用于消息去重 */
    private Set<Long> processedMsgIds = ConcurrentHashMap.newKeySet();

    /** 会话创建时间 */
    private LocalDateTime createdAt;

    /** 登录成功时间 */
    private LocalDateTime loggedInAt;

    /** 登录二维码内容 */
    private String qrContent;

    /** 会话状态 */
    private Status status;

    /**
     * 会话状态枚举。
     */
    public enum Status {
        /** 已创建，等待扫码 */
        WAITING_SCAN,
        /** 已登录，正在轮询消息 */
        ACTIVE,
        /** 已停止 */
        STOPPED
    }

    /**
     * 创建一个新的等待扫码的会话。
     *
     * @param sessionId 会话唯一标识
     * @param client    ILink 客户端实例
     * @param qrContent 登录二维码内容
     * @return 新会话对象
     */
    public static ChatSession create(String sessionId, ILinkClient client, String qrContent) {
        ChatSession session = new ChatSession();
        session.sessionId = sessionId;
        session.client = client;
        session.qrContent = qrContent;
        session.running = true;
        session.createdAt = LocalDateTime.now();
        session.status = Status.WAITING_SCAN;
        return session;
    }

    /**
     * 标记会话为已登录。
     *
     * @param botId 微信 bot ID
     */
    public void markLoggedIn(String botId) {
        this.botId = botId;
        this.loggedInAt = LocalDateTime.now();
        this.status = Status.ACTIVE;
    }

    /**
     * 标记会话为已停止。
     */
    public void markStopped() {
        this.running = false;
        this.status = Status.STOPPED;
    }
}
