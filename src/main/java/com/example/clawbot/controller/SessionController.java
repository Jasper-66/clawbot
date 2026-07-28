package com.example.clawbot.controller;

import com.example.clawbot.entity.ChatSession;
import com.example.clawbot.service.WeChatSessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 会话管理控制面板 — HTTP API。
 *
 * <p>提供多用户微信会话的管理能力：</p>
 * <ul>
 *   <li>{@code POST /api/sessions} — 创建新会话，返回二维码供用户扫码</li>
 *   <li>{@code GET /api/sessions} — 列出所有会话状态</li>
 *   <li>{@code GET /api/sessions/{id}} — 查询单个会话详情</li>
 *   <li>{@code DELETE /api/sessions/{id}} — 停止并移除会话</li>
 * </ul>
 *
 * @see com.example.clawbot.service.WeChatSessionManager
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final WeChatSessionManager sessionManager;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 创建新会话 — 生成二维码供用户扫码登录。
     *
     * @return 会话信息（包含 sessionId、二维码内容、状态）
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createSession() {
        try {
            ChatSession session = sessionManager.createSession();
            return ResponseEntity.ok(Map.of(
                    "sessionId", session.getSessionId(),
                    "status", session.getStatus().name(),
                    "qrContent", session.getQrContent(),
                    "createdAt", session.getCreatedAt().format(FMT),
                    "message", "请扫描二维码登录微信"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "创建会话失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 列出所有会话。
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listSessions() {
        List<Map<String, Object>> list = sessionManager.listSessions().stream()
                .map(this::toSessionInfo)
                .toList();
        return ResponseEntity.ok(list);
    }

    /**
     * 查询单个会话详情。
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionId) {
        ChatSession session = sessionManager.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toSessionInfo(session));
    }

    /**
     * 停止并移除会话。
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> removeSession(@PathVariable String sessionId) {
        boolean removed = sessionManager.removeSession(sessionId);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "message", "会话已停止并移除"
        ));
    }

    /**
     * 将会话对象转为 API 响应格式。
     */
    private Map<String, Object> toSessionInfo(ChatSession session) {
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("sessionId", session.getSessionId());
        info.put("status", session.getStatus().name());
        info.put("botId", session.getBotId());
        info.put("qrContent", session.getQrContent());
        info.put("createdAt", session.getCreatedAt() != null ? session.getCreatedAt().format(FMT) : null);
        info.put("loggedInAt", session.getLoggedInAt() != null ? session.getLoggedInAt().format(FMT) : null);
        return info;
    }
}
