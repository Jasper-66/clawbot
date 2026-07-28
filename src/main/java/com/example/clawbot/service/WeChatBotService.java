package com.example.clawbot.service;

import com.example.clawbot.entity.MessageLog;
import com.example.clawbot.repository.MessageLogRepository;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信消息路由服务 — 无状态的消息处理中枢。
 *
 * <p>负责根据消息类型（文本、图片、语音、文件）将消息分发到对应的业务服务处理。
 * 不持有任何 ILinkClient 实例，所有需要网络发送的方法均通过参数接收 client。</p>
 *
 * <h3>多会话架构</h3>
 * <p>每个用户会话由 {@link com.example.clawbot.service.WeChatSessionManager} 管理，
 * 拥有独立的 ILinkClient 和轮询线程。本类作为共享的消息处理逻辑层，
 * 被各会话的轮询线程并发调用。</p>
 *
 * <h3>消息处理流程</h3>
 * <pre>
 * WeChatSessionManager (per-session polling)
 *   ↓
 * WeChatBotService.handleMessage(client, msg)
 *   ├── 文本 → LlmService.chat() → handleLlmReply()
 *   ├── 图片 → LlmService.chatWithImage()
 *   ├── 语音 → SpeechService.speechToText() → 路由
 *   └── 文件 → FileSummaryService.summarizeFile()
 * </pre>
 *
 * @see com.example.clawbot.service.WeChatSessionManager
 * @see com.example.clawbot.service.LlmService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeChatBotService {

    private final LlmService llmService;
    private final ImageGenerationService imageGenerationService;
    private final SpeechService speechService;
    private final FileSummaryService fileSummaryService;
    private final MessageLogRepository messageLogRepository;

    /** 全局防重复发送：key=userId:replyHash → 上次发送时间戳 */
    private final Map<String, Long> recentlySent = new ConcurrentHashMap<>();

    // ==================== 消息路由（供 WeChatSessionManager 调用） ====================

    /**
     * 消息分发处理器 — 根据消息子项类型路由到对应处理方法。
     *
     * @param client 当前会话的 ILink 客户端
     * @param msg    微信消息对象
     */
    public void handleMessage(ILinkClient client, WeixinMessage msg) {
        String fromUser = msg.getFrom_user_id();
        if (msg.getItem_list() == null) return;

        msg.getItem_list().forEach(item -> {
            if (item.getText_item() != null) {
                String text = item.getText_item().getText();
                log.info("收到文本 from={}, text={}", fromUser, text);
                saveMessage(fromUser, text, "text", "in");

                if (isVoiceCommand(text)) {
                    handleVoiceCommand(client, fromUser, text);
                } else {
                    String reply = llmService.chat(fromUser, text);
                    handleLlmReply(client, fromUser, reply);
                }
            } else if (item.getImage_item() != null) {
                log.info("收到图片 from={}", fromUser);
                saveMessage(fromUser, "[图片消息]", "image", "in");
                try {
                    byte[] imageBytes = client.downloadImageFromMessageItem(item);
                    sendReply(client, fromUser, llmService.chatWithImage(fromUser, imageBytes, "image.jpg"));
                } catch (Exception e) {
                    log.error("下载或识别图片失败", e);
                    sendReply(client, fromUser, "抱歉，图片处理失败，请稍后再试。");
                }
            } else if (item.getVoice_item() != null) {
                log.info("收到语音 from={}, playtime={}s", fromUser, item.getVoice_item().getPlaytime());
                saveMessage(fromUser, "[语音消息]", "voice", "in");
                handleVoiceMessage(client, fromUser, item);
            } else if (item.getFile_item() != null) {
                FileItem fileItem = item.getFile_item();
                log.info("收到文件 from={}, fileName={}", fromUser, fileItem.getFile_name());
                saveMessage(fromUser, "[文件] " + fileItem.getFile_name(), "file", "in");
                handleFileMessage(client, fromUser, item, fileItem);
            }
        });
    }

    // ==================== 发送方法（供外部服务调用） ====================

    /**
     * 发送定时提醒消息。
     *
     * @param client  ILink 客户端
     * @param userId  目标用户 ID
     * @param message 提醒消息内容
     */
    public void sendReminder(ILinkClient client, String userId, String message) {
        try {
            client.sendText(userId, message);
            saveMessage(userId, message, "text", "out");
            log.info("定时提醒已发送: userId={}", userId);
        } catch (Exception e) {
            log.error("定时提醒发送失败: userId={}", userId, e);
        }
    }

    /**
     * 发送定时生成的图片。
     *
     * @param client      ILink 客户端
     * @param userId      目标用户 ID
     * @param imageBytes  图片二进制数据
     * @param description 图片描述
     */
    public void sendReminderImage(ILinkClient client, String userId, byte[] imageBytes, String description) {
        try {
            client.sendImage(userId, imageBytes, "定时生成.png", "image/png");
            saveMessage(userId, "[定时生成图片] " + description, "image", "out");
            log.info("定时图片已发送: userId={}", userId);
        } catch (Exception e) {
            log.error("定时图片发送失败: userId={}", userId, e);
        }
    }

    // ==================== 内部处理方法 ====================

    private void saveMessage(String userId, String content, String msgType, String direction) {
        try {
            messageLogRepository.save(MessageLog.builder()
                    .userId(userId)
                    .content(content)
                    .msgType(msgType)
                    .direction(direction)
                    .build());
        } catch (Exception e) {
            log.warn("消息入库失败: userId={}, type={}, direction={}", userId, msgType, direction, e);
        }
    }

    private void sendReply(ILinkClient client, String toUser, String reply) {
        String key = toUser + ":" + reply.hashCode();
        Long lastSent = recentlySent.get(key);
        if (lastSent != null && System.currentTimeMillis() - lastSent < 3000) {
            log.warn("跳过重复发送: toUser={}", toUser);
            return;
        }
        recentlySent.put(key, System.currentTimeMillis());
        if (recentlySent.size() > 100) {
            long cutoff = System.currentTimeMillis() - 10000;
            recentlySent.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
        try {
            client.sendText(toUser, reply);
            saveMessage(toUser, reply, "text", "out");
        } catch (Exception e) {
            log.error("发送消息失败", e);
        }
    }

    private void handleLlmReply(ILinkClient client, String fromUser, String reply) {
        if (reply == null || reply.isEmpty()) return;

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\[audio:(.+?)]")
                .matcher(reply);
        if (matcher.find()) {
            String audioPath = matcher.group(1);
            String remainingText = reply.replace(matcher.group(), "").trim();
            java.io.File audioFile = new java.io.File(audioPath);
            if (audioFile.exists() && audioFile.isFile()) {
                try {
                    byte[] audioBytes = java.nio.file.Files.readAllBytes(audioFile.toPath());
                    client.sendFile(fromUser, audioBytes, "语音回复.wav", "");
                    log.info("LLM 触发的语音已发送: path={}, size={} bytes", audioPath, audioBytes.length);
                    if (!remainingText.isEmpty()) {
                        sendReply(client, fromUser, remainingText);
                    }
                    try { audioFile.delete(); } catch (Exception ignored) {}
                    return;
                } catch (Exception e) {
                    log.error("读取或发送 LLM 触发的语音失败: path={}", audioPath, e);
                }
            } else {
                log.warn("LLM 标记的音频文件不存在: path={}", audioPath);
                if (!remainingText.isEmpty()) {
                    try {
                        log.info("尝试兜底生成语音: textLength={}", remainingText.length());
                        client.sendTextWithTyping(fromUser, "正在生成语音...", 500);
                        byte[] audioData = speechService.textToSpeech(fromUser, remainingText);
                        client.sendFile(fromUser, audioData, "语音播报.wav", "");
                        log.info("兜底语音已生成并发送: textLength={}", remainingText.length());
                        return;
                    } catch (Exception e) {
                        log.error("兜底语音生成失败", e);
                    }
                }
            }
        }
        sendReply(client, fromUser, reply);
    }

    private void handleVoiceMessage(ILinkClient client, String fromUser, MessageItem item) {
        try {
            client.sendTextWithTyping(fromUser, "正在识别语音，请稍候...", 500);
            byte[] voiceBytes = client.downloadVoiceFromMessageItem(item);

            String fileName = "voice.amr";
            Integer encodeType = item.getVoice_item().getEncode_type();
            if (encodeType != null && encodeType == 4) fileName = "voice.sil";
            else if (encodeType != null && encodeType == 0) fileName = "voice.wav";

            String recognizedText = speechService.speechToText(voiceBytes, fileName);
            log.info("语音识别结果: text=[{}], isImageGen={}", recognizedText, isImageGenRequest(recognizedText));

            if (isImageGenRequest(recognizedText)) {
                handleImageGeneration(client, fromUser, recognizedText);
            } else {
                String llmReply = llmService.chat(fromUser, recognizedText);
                handleLlmReply(client, fromUser, llmReply);
            }
        } catch (Exception e) {
            log.error("语音处理失败", e);
            try {
                client.sendText(fromUser, "抱歉，语音处理失败了，请稍后再试。");
            } catch (Exception ex) {
                log.error("发送错误消息失败", ex);
            }
        }
    }

    private void handleFileMessage(ILinkClient client, String fromUser, MessageItem item, FileItem fileItem) {
        try {
            client.sendTextWithTyping(fromUser, "正在查看文件，请稍候...", 500);
            byte[] fileBytes = client.downloadFileFromMessageItem(item);
            String summary = fileSummaryService.summarizeFile(fileBytes, fileItem.getFile_name());
            sendReply(client, fromUser, summary);
        } catch (Exception e) {
            log.error("文件处理失败", e);
            sendReply(client, fromUser, "抱歉，文件处理失败，请稍后再试。");
        }
    }

    private void handleImageGeneration(ILinkClient client, String fromUser, String text) {
        try {
            client.sendTextWithTyping(fromUser, "正在为你生成图片，请稍候...", 500);
        } catch (Exception e) {
            log.error("发送提示消息失败", e);
        }
        try {
            String prompt = extractImagePrompt(text);
            log.info("图片生成请求: prompt={}", prompt);
            byte[] imageBytes = imageGenerationService.generateImage(prompt);
            client.sendImage(fromUser, imageBytes, "generated.png", "image/png");
            log.info("图片已发送给 {}", fromUser);
        } catch (Exception e) {
            log.error("图片生成失败", e);
            try {
                client.sendText(fromUser, "抱歉，图片生成失败，请稍后再试。");
            } catch (Exception ex) {
                log.error("发送错误消息失败", ex);
            }
        }
    }

    private boolean isImageGenRequest(String text) {
        if (text.contains("生成图片") || text.contains("生成图像") || text.contains("生成一张")
                || text.contains("生成个图") || text.contains("图片生成") || text.contains("图像生成")
                || text.contains("画一个") || text.contains("画一张") || text.contains("画个")
                || text.contains("画一只") || text.contains("画只") || text.contains("画幅")
                || text.contains("帮我画") || text.contains("做个图") || text.contains("做一张图")
                || text.contains("来一张") || text.contains("来张")) {
            return true;
        }
        return (text.contains("生成") || text.contains("画"))
                && (text.contains("图片") || text.contains("图像") || text.contains("照片"));
    }

    private String extractImagePrompt(String text) {
        String prompt = text
                .replaceAll("生成图片|生成图像|生成一张|生成个图|画一个|画一张|画个|画一只|画只|画幅|帮我画|图片生成|图像生成|做一张图|做个图|来一张|来张|生成|图片|图像|照片|图", "")
                .trim();
        return prompt.isEmpty() ? text : prompt;
    }

    private boolean isVoiceCommand(String text) {
        return text.startsWith("切换音色") || text.startsWith("设置音色")
                || text.startsWith("换成音色") || text.startsWith("更换音色")
                || text.equals("音色列表") || text.equals("有哪些音色")
                || text.equals("当前音色") || text.equals("我的音色")
                || text.startsWith("音色");
    }

    private void handleVoiceCommand(ILinkClient client, String fromUser, String text) {
        if (text.equals("音色列表") || text.equals("有哪些音色")) {
            sendReply(client, fromUser, speechService.getAvailableVoices());
        } else if (text.equals("当前音色") || text.equals("我的音色")) {
            sendReply(client, fromUser, "当前音色：" + speechService.getCurrentVoice(fromUser));
        } else {
            String voiceName = text.replaceFirst("^(切换音色|设置音色|换成音色|更换音色|音色)", "").trim();
            sendReply(client, fromUser, speechService.setVoice(fromUser, voiceName));
        }
    }
}
