package com.example.clawbot.service;

import com.example.clawbot.entity.ChatSession;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信多会话管理器 — 管理多个独立的微信登录会话。
 *
 * <p>每个会话拥有独立的 {@link ILinkClient} 实例和消息轮询线程，
 * 多个用户可以顺序登录并独立与机器人聊天。</p>
 *
 * <h3>会话生命周期</h3>
 * <pre>
 * POST /api/sessions → createSession()
 *   → 创建 ILinkClient → executeLogin() → 返回二维码
 *   → 等待扫码 → 登录成功 → 启动轮询线程
 *   → 每 2 秒调用 WeChatBotService.handleMessage() 处理消息
 *
 * DELETE /api/sessions/{id} → removeSession()
 *   → running=false → client.close()
 * </pre>
 *
 * @see com.example.clawbot.service.WeChatBotService
 * @see com.example.clawbot.entity.ChatSession
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeChatSessionManager {

    private final WeChatBotService weChatBotService;

    /** 活跃会话表：sessionId → ChatSession */
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    /**
     * 应用启动时自动创建第一个会话，生成登录二维码。
     */
    @PostConstruct
    public void init() {
        try {
            ChatSession session = createSession();
            log.info("========================================");
            log.info("自动创建会话成功，sessionId={}", session.getSessionId());
            log.info("请扫描以下二维码登录微信：");
            log.info(session.getQrContent());
            log.info("========================================");
        } catch (Exception e) {
            log.error("自动创建会话失败", e);
        }
    }

    /**
     * 创建新会话 — 构建 ILinkClient 并执行登录，返回二维码内容。
     *
     * <p>登录过程是异步的：方法立即返回二维码内容和 sessionId，
     * 后台线程等待用户扫码后自动启动消息轮询。</p>
     *
     * @return 已创建的会话对象（状态为 WAITING_SCAN）
     * @throws Exception ILinkClient 构建或登录失败
     */
    public ChatSession createSession() throws Exception {
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        ILinkClient client = ILinkClient.builder()
                .config(ILinkConfig.builder()
                        .connectTimeoutMs(35000)
                        .readTimeoutMs(35000)
                        .httpMaxRetries(3)
                        .heartbeatEnabled(true)
                        .build())
                .build();

        // 获取登录二维码
        String qrContent = client.executeLogin();

        ChatSession session = ChatSession.create(sessionId, client, qrContent);
        sessions.put(sessionId, session);
        log.info("会话 [{}] 已创建，等待扫码登录", sessionId);

        // 异步等待扫码成功后启动轮询
        CompletableFuture.runAsync(() -> {
            try {
                client.getLoginFuture().get();
                String botId = client.getLoginContext().getBotId();
                session.markLoggedIn(botId);
                log.info("会话 [{}] 登录成功，botId={}", sessionId, botId);

                // 自动为下一个用户创建会话
                createNextSession();

                pollMessages(session);
            } catch (Exception e) {
                log.error("会话 [{}] 登录失败", sessionId, e);
                session.markStopped();
            }
        });

        return session;
    }

    /**
     * 自动为下一个用户创建会话并打印二维码。
     */
    private void createNextSession() {
        try {
            ChatSession nextSession = createSession();
            log.info("========================================");
            log.info("已自动为下一位用户创建会话，sessionId={}", nextSession.getSessionId());
            log.info("请扫描以下二维码登录微信：");
            log.info(nextSession.getQrContent());
            log.info("========================================");
        } catch (Exception e) {
            log.error("自动创建下一个会话失败", e);
        }
    }

    /**
     * 移除并停止指定会话。
     *
     * @param sessionId 会话 ID
     * @return true 如果会话存在并已停止
     */
    public boolean removeSession(String sessionId) {
        ChatSession session = sessions.remove(sessionId);
        if (session == null) return false;

        session.markStopped();
        try {
            session.getClient().close();
        } catch (Exception e) {
            log.warn("关闭会话 [{}] 客户端失败", sessionId, e);
        }
        log.info("会话 [{}] 已移除", sessionId);
        return true;
    }

    /**
     * 获取指定会话。
     *
     * @param sessionId 会话 ID
     * @return 会话对象，不存在返回 null
     */
    public ChatSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 获取所有会话列表。
     */
    public List<ChatSession> listSessions() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * 获取一个活跃的 ILinkClient（用于定时提醒等场景）。
     *
     * <p>返回第一个状态为 ACTIVE 的会话的客户端。
     * 如果没有活跃会话，返回 null。</p>
     */
    public ILinkClient getActiveClient() {
        for (ChatSession session : sessions.values()) {
            if (session.getStatus() == ChatSession.Status.ACTIVE) {
                return session.getClient();
            }
        }
        return null;
    }

    /**
     * 消息轮询主循环 — 每 2 秒拉取一次微信消息。
     *
     * @param session 当前会话
     */
    private void pollMessages(ChatSession session) {
        ILinkClient client = session.getClient();
        log.info("会话 [{}] 开始消息轮询", session.getSessionId());

        while (session.isRunning()) {
            try {
                List<WeixinMessage> messages = client.getUpdates();
                for (WeixinMessage msg : messages) {
                    Long msgId = msg.getMessage_id();
                    if (msgId != null && !session.getProcessedMsgIds().add(msgId)) {
                        continue;
                    }
                    weChatBotService.handleMessage(client, msg);
                }
                if (session.getProcessedMsgIds().size() > 500) {
                    session.getProcessedMsgIds().clear();
                }
            } catch (Exception e) {
                if (session.isRunning()) {
                    log.error("会话 [{}] 消息轮询异常", session.getSessionId(), e);
                }
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("会话 [{}] 消息轮询已停止", session.getSessionId());
    }

    /**
     * 应用关闭时停止所有会话。
     */
    @PreDestroy
    public void destroy() {
        for (ChatSession session : sessions.values()) {
            session.markStopped();
            try {
                session.getClient().close();
            } catch (Exception ignored) {}
        }
        sessions.clear();
        log.info("所有微信会话已关闭");
    }
}
