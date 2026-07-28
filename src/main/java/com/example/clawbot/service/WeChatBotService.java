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
                String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                log.info("══════════ [思考] 收到文本消息 ══════════");
                log.info("  发信人: {}", fromUser);
                log.info("  内容: \"{}\"", preview);
                log.info("  → 按优先级匹配关键词: 音色命令 → TTS请求 → 图片生成 → 兜底LLM对话");
                saveMessage(fromUser, text, "text", "in");

                try {
                    if (isVoiceCommand(text)) {
                        log.info("[行动] 匹配到「音色命令」关键词，路由到语音管理模块");
                        handleVoiceCommand(client, fromUser, text);
                    } else if (isTtsRequest(text)) {
                        log.info("[行动] 匹配到「TTS朗读」关键词，路由到语音合成模块");
                        handleTts(client, fromUser, extractTtsText(text));
                    } else if (isImageGenRequest(text)) {
                        log.info("[行动] 匹配到「图片生成」关键词，路由到AI绘图模块");
                        handleImageGeneration(client, fromUser, text);
                    } else {
                        log.info("[行动] 未命中特殊关键词，作为通用对话路由到 LLM 服务 (DeepSeek Function Calling)");
                        String reply = llmService.chat(fromUser, text);
                        handleLlmReply(client, fromUser, reply);
                    }
                } catch (Exception e) {
                    log.error("[异常] 文本消息处理失败 | 用户: {} | 内容: \"{}\" | 原因: {} | 建议: 检查 LLM 服务和网络连接", fromUser, preview, e.getMessage(), e);
                    sendReply(client, fromUser, "抱歉，消息处理失败，请稍后再试。");
                }
            } else if (item.getImage_item() != null) {
                // ── 图片消息 ──
                log.info("══════════ [思考] 收到图片消息 ══════════");
                log.info("  发信人: {}", fromUser);
                log.info("  → 需要: 下载图片 → 调用视觉模型识别 → 生成文字回复");
                saveMessage(fromUser, "[图片消息]", "image", "in");
                try {
                    byte[] imageBytes = client.downloadImageFromMessageItem(item);
                    log.info("[行动] 图片下载完成 ({} bytes)，发送到 DashScope 视觉模型进行内容识别", imageBytes.length);
                    String result = llmService.chatWithImage(fromUser, imageBytes, "image.jpg");
                    sendReply(client, fromUser, result);
                } catch (Exception e) {
                    log.error("[异常] 图片消息处理失败 | 用户: {} | 原因: {} | 建议: 检查网络和视觉API密钥", fromUser, e.getMessage(), e);
                    sendReply(client, fromUser, "抱歉，图片处理失败，请稍后再试。");
                }
            } else if (item.getVoice_item() != null) {
                // ── 语音消息 ──
                log.info("══════════ [思考] 收到语音消息 (时长{}s) ══════════", item.getVoice_item().getPlaytime());
                log.info("  发信人: {}", fromUser);
                log.info("  → 需要: 下载语音文件 → SILK解码 → ASR语音识别 → 文本路由");
                saveMessage(fromUser, "[语音消息]", "voice", "in");
                handleVoiceMessage(client, fromUser, item);
            } else if (item.getFile_item() != null) {
                FileItem fileItem = item.getFile_item();
                log.info("══════════ [思考] 收到文件消息: {} ══════════", fileItem.getFile_name());
                log.info("  发信人: {}", fromUser);
                log.info("  → 需要: 下载文件 → 提取文本(PDF/DOCX/XLSX等) → LLM生成摘要");
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
            client.sendTextWithTyping(toUser, reply, 1500);
            saveMessage(toUser, reply, "text", "out");
            log.info("[最终结果] 文本回复已发送到微信 ({}字符)", reply.length());
        } catch (Exception e) {
            log.error("[异常] 消息发送失败 | 收信人: {} | 原因: {} | 建议: 检查微信 ILink 连接状态", toUser, e.getMessage(), e);
        }
    }

    private void handleLlmReply(ILinkClient client, String fromUser, String reply) {
        if (reply == null || reply.isEmpty()) {
            log.info("[观察] LLM 返回了空内容，不执行发送操作");
            return;
        }
        log.info("[观察] LLM 返回响应 ({}字符)，检查是否包含语音标记 [audio:...]", reply.length());
        // 使用正则匹配 [audio:文件路径] 标记
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\[audio:(.+?)]")
                .matcher(reply);
        if (matcher.find()) {
            String audioPath = matcher.group(1);
            String remainingText = reply.replace(matcher.group(), "").trim();
            log.info("[行动] 检测到 LLM 已调用 text_to_speech 工具，生成了语音文件: {}", audioPath);
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
                    log.error("[异常] 语音文件发送失败 | 文件: {} | 原因: {} | 建议: 检查文件是否损坏", audioPath, e.getMessage(), e);
                }
            } else {
                log.warn("LLM 标记的音频文件不存在: path={}，尝试兜底生成语音", audioPath);
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
        log.info("[行动] 开始处理语音消息: 下载语音文件 → 检测编码格式 → ASR语音识别");
        try {
            client.sendTextWithTyping(fromUser, "正在识别语音，请稍候...", 500);

            // 下载语音文件
            byte[] voiceBytes = client.downloadVoiceFromMessageItem(item);
            log.info("  ├─ 语音文件下载完成 ({} bytes)", voiceBytes.length);

            // 根据微信编码类型确定文件格式
            String fileName = "voice.amr";  // 默认 AMR
            Integer encodeType = item.getVoice_item().getEncode_type();
            if (encodeType != null && encodeType == 4) fileName = "voice.sil";   // SILK
            else if (encodeType != null && encodeType == 0) fileName = "voice.wav"; // WAV
            log.info("  ├─ 编码格式: encodeType={} → 文件类型: {}", encodeType, fileName);

            // ASR 识别
            String recognizedText = speechService.speechToText(voiceBytes, fileName);
            log.info("  ├─ [观察] ASR语音识别结果: \"{}\"", recognizedText);

            // 根据识别结果路由：图片生成 或 LLM 对话
            if (isImageGenRequest(recognizedText)) {
                log.info("  └─ 识别文本匹配「图片生成」关键词 → 路由到AI绘图");
                handleImageGeneration(client, fromUser, recognizedText);
            } else {
                log.info("  └─ 识别文本作为对话 → 路由到 LLM 服务");
                String llmReply = llmService.chat(fromUser, recognizedText);
                handleLlmReply(client, fromUser, llmReply);
            }

        } catch (Exception e) {
            log.error("[异常] 语音消息处理失败 | 用户: {} | 原因: {} | 建议: 检查网络和DashScope ASR API密钥", fromUser, e.getMessage(), e);
            try {
                client.sendText(fromUser, "抱歉，语音处理失败了，请稍后再试。");
            } catch (Exception ex) {
                log.error("发送错误提示也失败了: {}", ex.getMessage());
            }
        }
    }

    private void handleFileMessage(ILinkClient client, String fromUser, MessageItem item, FileItem fileItem) {
        log.info("[行动] 开始处理文件: {} → 下载 → 提取文本 → LLM生成摘要", fileItem.getFile_name());
        try {
            client.sendTextWithTyping(fromUser, "正在查看文件，请稍候...", 500);
            byte[] fileBytes = client.downloadFileFromMessageItem(item);
            log.info("  ├─ 文件下载完成 ({} bytes)", fileBytes.length);
            //调用FileSummaryService来提取内容并生成摘要
            String summary = fileSummaryService.summarizeFile(fileBytes, fileItem.getFile_name());
            log.info("  └─ [观察] 文件总结完成，生成摘要 ({}字符)", summary != null ? summary.length() : 0);
            sendReply(client, fromUser, summary);
        } catch (Exception e) {
            log.error("[异常] 文件处理失败 | 文件: {} | 用户: {} | 原因: {} | 建议: 检查文件格式是否支持", fileItem.getFile_name(), fromUser, e.getMessage(), e);
            sendReply(client, fromUser, "抱歉，文件处理失败，请稍后再试。");
        }
    }

    private void handleImageGeneration(ILinkClient client, String fromUser, String text) {
        String prompt = extractImagePrompt(text);
        log.info("[行动] AI图片生成: 从用户输入提取绘图提示词 → \"{}\"", prompt.length() > 80 ? prompt.substring(0, 80) + "..." : prompt);
        try {
            client.sendTextWithTyping(fromUser, "正在为你生成图片，请稍候...", 500);
        } catch (Exception e) {
            log.error("发送\"正在生成\"提示失败: {}", e.getMessage());
        }
        try {
            byte[] imageBytes = imageGenerationService.generateImage(prompt);
            log.info("[观察] 智谱 CogView 生成图片成功 ({} bytes)，准备发送给用户", imageBytes.length);
            client.sendImage(fromUser, imageBytes, "generated.png", "image/png");
            log.info("[最终结果] AI生成图片已发送到微信 ({} bytes)", imageBytes.length);
        } catch (Exception e) {
            log.error("[异常] 图片生成失败 | prompt: \"{}\" | 原因: {} | 建议: 检查智谱 CogView API 密钥", prompt, e.getMessage(), e);
            try {
                client.sendText(fromUser, "抱歉，图片生成失败，请稍后再试。");
            } catch (Exception ex) {
                log.error("发送错误提示也失败了: {}", ex.getMessage());
            }
        }
    }

    private boolean isTtsRequest(String text) {
        return text.startsWith("朗读") || text.startsWith("读一下")
                || text.startsWith("语音说") || text.startsWith("语音播报")
                || text.startsWith("转语音") || text.startsWith("语音回复");
    }

    private String extractTtsText(String text) {
        return text.replaceFirst("^(朗读|读一下|语音说|语音播报|转语音|语音回复)", "").trim();
    }

    private void handleTts(ILinkClient client, String fromUser, String textToRead) {
        log.info("[行动] TTS语音合成: 将 \"{}\" 发送到 DashScope TTS 引擎", textToRead.length() > 80 ? textToRead.substring(0, 80) + "..." : textToRead);
        try {
            client.sendTextWithTyping(fromUser, "正在生成语音...", 500);
            byte[] audioData = speechService.textToSpeech(fromUser, textToRead);
            log.info("[观察] DashScope TTS 合成成功，生成WAV音频 ({} bytes)", audioData.length);
            client.sendFile(fromUser, audioData, "语音播报.wav", "");
            log.info("[最终结果] 语音播报文件已发送到微信 ({} bytes)", audioData.length);
        } catch (Exception e) {
            log.error("[异常] TTS语音合成失败 | 文本: \"{}\" | 原因: {} | 建议: 检查 DashScope TTS API 密钥", textToRead, e.getMessage(), e);
            sendReply(client, fromUser, "语音生成失败，请稍后再试。");
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

    // ==================== 音色命令处理（增强版） ====================

    static boolean isVoiceCommand(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String command = stripVoiceCommandPoliteness(text);
        if (isVoiceListRequest(command) || isCurrentVoiceRequest(command)) {
            return true;
        }
        return command.matches("^(切换|设置|更换|选择|换|改).*?(音色|声音).*$")
                || command.matches("^(使用|用|换成|改成).+(音色|声音)$")
                || command.matches("^(把)?(音色|声音).*(换成|改成|设置为|切换为).+$")
                || command.matches("^(音色|声音)\\s*[:：]?\\s*\\S+$");
    }

    private void handleVoiceCommand(ILinkClient client, String fromUser, String text) {
        String command = stripVoiceCommandPoliteness(text);
        log.info("[行动] 处理音色管理命令: \"{}\"", command);
        if (isVoiceListRequest(command)) {
            log.info("  → 查询可用音色列表 (共15种音色)");
            String result = speechService.getAvailableVoices();
            log.info("[最终结果] 音色列表已发送 ({}字符)", result.length());
            sendReply(client, fromUser, result);
        } else if (isCurrentVoiceRequest(command)) {
            log.info("  → 查询用户当前音色");
            sendReply(client, fromUser, "当前音色：" + speechService.getCurrentVoice(fromUser));
        } else {
            String voiceName = extractVoiceName(command);
            log.info("  → 切换音色: {}", voiceName.isEmpty() ? "(未指定具体音色)" : voiceName);
            sendReply(client, fromUser, speechService.setVoice(fromUser, voiceName));
        }
    }

    private static boolean isVoiceListRequest(String text) {
        return text.equals("音色列表") || text.equals("声音列表")
                || text.equals("有哪些音色") || text.equals("有哪些声音")
                || text.matches(".*(有哪些|有什么|支持哪些|可选哪些|可以用哪些).*(音色|声音).*")
                || text.matches(".*(音色|声音).*(列表|有哪些|有什么|可选).*");
    }

    private static boolean isCurrentVoiceRequest(String text) {
        return text.equals("当前音色") || text.equals("我的音色")
                || text.equals("当前声音") || text.equals("我的声音")
                || text.matches(".*(当前|现在|正在|我的).*(音色|声音).*(什么|哪个|哪种).*")
                || text.matches(".*(当前|现在|正在|我的).*(什么|哪个|哪种).*(音色|声音).*");
    }

    static String extractVoiceName(String text) {
        if (text == null) {
            return "";
        }
        String voiceName = stripVoiceCommandPoliteness(text)
                .replaceFirst("^(把)?(音色|声音)\\s*(切换|设置|更换|换|改)\\s*(为|成|到)?\\s*", "")
                .replaceFirst("^(切换|设置|更换)\\s*(一下)?\\s*(音色|声音)\\s*(为|成|到)?\\s*", "")
                .replaceFirst("^(换|改)\\s*(一个|个|一下)?\\s*(音色|声音)\\s*(为|成|到)?\\s*", "")
                .replaceFirst("^(切换为|设置为|更换为|使用|选择|用|换成|改成)\\s*", "")
                .replaceFirst("^(音色|声音)\\s*[:：]?\\s*", "")
                .replaceFirst("\\s*的?\\s*(音色|声音)$", "")
                .replaceAll("^[：:，,。\\s]+|[。！!，,\\s]+$", "")
                .trim();
        return voiceName.matches("^(一个|个|一下)$") ? "" : voiceName;
    }

    private static String stripVoiceCommandPoliteness(String text) {
        return text.trim()
                .replaceFirst("^(请帮我|麻烦帮我|帮我|麻烦|我想|我要|给我|请)\\s*", "")
                .trim();
    }
}
