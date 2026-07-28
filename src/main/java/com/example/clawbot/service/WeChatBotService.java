package com.example.clawbot.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 微信机器人核心服务，负责登录、消息轮询、消息路由和回复发送。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeChatBotService {

    /** 匹配 LLM 回复中的 [audio:文件路径] 语音标记 */
    private static final java.util.regex.Pattern AUDIO_MARKER_PATTERN =
            java.util.regex.Pattern.compile("\\[audio:(.+?)]");

    private final LlmService llmService;
    private final ImageGenerationService imageGenerationService;
    private final SpeechService speechService;
    private final FileSummaryService fileSummaryService;
    private final ConversationMemoryService memoryService;
    private ILinkClient client;

    private volatile boolean running = true;

    /** 应用启动后异步初始化微信机器人，避免阻塞 Spring 启动。 */
    @PostConstruct
    public void init() {
        CompletableFuture.runAsync(this::startBot);
    }

    /** 构建 ILink 客户端并登录微信。 */
    private void startBot() {
        try {
            // 构建客户端：配置连接超时、读超时、重试次数、心跳
            client = ILinkClient.builder()
                    .config(ILinkConfig.builder()
                            .connectTimeoutMs(35000)
                            .readTimeoutMs(35000)
                            .httpMaxRetries(3)
                            .heartbeatEnabled(true)
                            .build())
                    .build();

            // 获取登录二维码（文本形式，可在终端/日志中扫码）
            String qrContent = client.executeLogin();
            log.info("===== 微信机器人登录 =====");
            log.info("请扫描以下二维码登录 ClawBot：");
            log.info(qrContent);
            log.info("=========================");

            // 阻塞等待用户扫码登录
            client.getLoginFuture().get();
            log.info("ClawBot 登录成功，botId={}", client.getLoginContext().getBotId());

            // 开始轮询消息
            pollMessages();

        } catch (Exception e) {
            log.error("ClawBot 启动失败", e);
        }
    }

    /** 轮询微信消息，每 2 秒拉取一次。 */
    private void pollMessages() {
        while (running) {
            try {
                // 主动向微信服务器发起 HTTP 请求，获取最新消息列表
                List<WeixinMessage> messages = client.getUpdates();
                for (WeixinMessage msg : messages) {
                    Long msgId = msg.getMessage_id();
                    // 去重：markProcessed 返回 false 表示消息已处理过（Redis Set 持久化去重）
                    if (msgId != null && !memoryService.markProcessed(msgId)) {
                        continue;
                    }
                    handleMessage(msg);
                }
            } catch (Exception e) {
                if (running) {
                    log.error("消息轮询异常", e);
                }
            }

            // 轮询间隔：2 秒
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    /** 消息分发：文本→关键词路由、图片→视觉识别、语音→ASR、文件→摘要。 */
    private void handleMessage(WeixinMessage msg) {
        String fromUser = msg.getFrom_user_id();
        if (msg.getItem_list() == null) return;

        msg.getItem_list().forEach(item -> {
            if (item.getText_item() != null) {
                // ── 文本消息 ──
                String text = item.getText_item().getText();
                log.info("收到文本 from={}, text={}", fromUser, text);

                // 按优先级依次检查关键词
                if (isVoiceCommand(text)) {
                    // 音色切换优先级最高
                    handleVoiceCommand(fromUser, text);
                } else if (isTtsRequest(text)) {
                    // TTS 请求："朗读 xxx"
                    handleTts(fromUser, extractTtsText(text));
                } else if (isImageGenRequest(text)) {
                    // 图片生成："画一个 xxx"
                    handleImageGeneration(fromUser, text);
                } else {
                    // 兜底：LLM 对话
                    String reply = llmService.chat(fromUser, text);
                    handleLlmReply(fromUser, reply);
                }
            } else if (item.getImage_item() != null) {
                // ── 图片消息 ──
                log.info("收到图片 from={}", fromUser);
                try {
                    byte[] imageBytes = client.downloadImageFromMessageItem(item);
                    sendReply(fromUser, llmService.chatWithImage(fromUser, imageBytes, "image.jpg"));
                } catch (Exception e) {
                    log.error("下载或识别图片失败", e);
                    sendReply(fromUser, "抱歉，图片处理失败，请稍后再试。");
                }
            } else if (item.getVoice_item() != null) {
                // ── 语音消息 ──
                log.info("收到语音 from={}, playtime={}s",
                        fromUser, item.getVoice_item().getPlaytime());
                handleVoiceMessage(fromUser, item);
            } else if (item.getFile_item() != null) {
                // ── 文件消息 ──
                FileItem fileItem = item.getFile_item();
                log.info("收到文件 from={}, fileName={}", fromUser, fileItem.getFile_name());
                handleFileMessage(fromUser, item, fileItem);
            }
        });
    }

    /** 发送文本回复（带"正在输入..."状态模拟），异常静默忽略。 */
    private void sendReply(String toUser, String reply) {
        try {
            client.sendTextWithTyping(toUser, reply, 1500);
        } catch (Exception e) {
            log.error("发送消息失败", e);
        }
    }

    /** 处理 LLM 回复中的 [audio:...] 语音标记并发送语音，剩余文字作为文本发送。 */
    private void handleLlmReply(String fromUser, String reply) {
        if (reply == null || reply.isEmpty()) {
            return;
        }
        java.util.regex.Matcher matcher = AUDIO_MARKER_PATTERN.matcher(reply);
        if (matcher.find()) {
            String audioPath = matcher.group(1);
            log.info("检测到音频标记: path={}, reply前100字={}", audioPath,
                    reply.length() > 100 ? reply.substring(0, 100) : reply);
            java.io.File audioFile = new java.io.File(audioPath);
            if (audioFile.exists() && audioFile.isFile()) {
                try {
                    byte[] audioBytes = java.nio.file.Files.readAllBytes(audioFile.toPath());
                    client.sendFile(fromUser, audioBytes, "语音回复.wav", "");
                    log.info("LLM 触发的语音已发送: path={}, size={} bytes", audioPath, audioBytes.length);

                    String remaining = reply.replace(matcher.group(), "").trim();
                    if (!remaining.isEmpty()) {
                        sendReply(fromUser, remaining);
                    }

                    try {
                        audioFile.delete();
                    } catch (Exception ignored) {
                    }
                    return;
                } catch (Exception e) {
                    log.error("读取或发送 LLM 触发的语音失败: path={}", audioPath, e);
                }
            } else {
                log.warn("LLM 标记的音频文件不存在: path={}", audioPath);
            }
            // 音频发送失败或文件不存在 → 清理 [audio:...] 标记后以文本发送
            String cleaned = AUDIO_MARKER_PATTERN.matcher(reply).replaceAll("").trim();
            if (!cleaned.isEmpty()) {
                sendReply(fromUser, cleaned);
            }
            return;
        }
        // 无音频标记 → 作为普通文本发送
        log.info("无音频标记，文本回复: reply前100字={}",
                reply.length() > 100 ? reply.substring(0, 100) : reply);
        sendReply(fromUser, reply);
    }

    /** 通过关键词匹配判断是否为图片生成请求。 */
    private boolean isImageGenRequest(String text) {
        if (text.contains("生成图片") || text.contains("生成图像") || text.contains("生成一张")
                || text.contains("生成个图") || text.contains("图片生成") || text.contains("图像生成")
                || text.contains("画一个") || text.contains("画一张") || text.contains("画个")
                || text.contains("画一只") || text.contains("画只") || text.contains("画幅")
                || text.contains("帮我画") || text.contains("做个图") || text.contains("做一张图")
                || text.contains("来一张") || text.contains("来张")) {
            return true;
        }
        // 组合条件：（生成 或 画）且 （图片 或 图像 或 照片）
        return (text.contains("生成") || text.contains("画"))
                && (text.contains("图片") || text.contains("图像") || text.contains("照片"));
    }

    /** 调用 AI 生成图片并发送给用户。 */
    private void handleImageGeneration(String fromUser, String text) {
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

    /** 从用户消息中移除触发关键词，提取图片生成提示词。 */
    private String extractImagePrompt(String text) {
        String prompt = text
                .replaceAll("生成图片|生成图像|生成一张|生成个图|画一个|画一张|画个|画一只|画只|画幅|帮我画|图片生成|图像生成|做一张图|做个图|来一张|来张|生成|图片|图像|照片|图", "")
                .trim();
        return prompt.isEmpty() ? text : prompt;
    }

    /** 通过前缀匹配判断是否为 TTS 请求。 */
    private boolean isTtsRequest(String text) {
        return text.startsWith("朗读") || text.startsWith("读一下")
                || text.startsWith("语音说") || text.startsWith("语音播报")
                || text.startsWith("转语音") || text.startsWith("语音回复");
    }

    /** 从 TTS 请求中移除前缀关键词，提取待朗读文本。 */
    private String extractTtsText(String text) {
        return text.replaceFirst("^(朗读|读一下|语音说|语音播报|转语音|语音回复)", "").trim();
    }

    /** TTS 文字转语音并发送语音文件给用户。 */
    private void handleTts(String fromUser, String textToRead) {
        try {
            // 发送输入状态提示
            client.sendTextWithTyping(fromUser, "正在生成语音...", 500);
            byte[] audioData = speechService.textToSpeech(fromUser, textToRead);
            client.sendFile(fromUser, audioData, "语音播报.wav", "");
            log.info("语音文件已发送给 {}", fromUser);
        } catch (Exception e) {
            log.error("TTS 失败", e);
            sendReply(fromUser, "语音生成失败，请稍后再试。");
        }
    }

    /** 处理语音消息：下载 → ASR 识别 → 路由识别文本到图片生成或 LLM。 */
    private void handleVoiceMessage(String fromUser, MessageItem item) {
        try {
            client.sendTextWithTyping(fromUser, "正在识别语音，请稍候...", 500);

            // 下载语音文件
            byte[] voiceBytes = client.downloadVoiceFromMessageItem(item);

            // 根据微信编码类型确定文件格式
            String fileName = "voice.amr";  // 默认 AMR
            Integer encodeType = item.getVoice_item().getEncode_type();
            if (encodeType != null && encodeType == 4) fileName = "voice.sil";   // SILK
            else if (encodeType != null && encodeType == 0) fileName = "voice.wav"; // WAV

            // ASR 识别
            String recognizedText = speechService.speechToText(voiceBytes, fileName);
            log.info("语音识别结果: text=[{}], isImageGen={}",
                    recognizedText, isImageGenRequest(recognizedText));

            // 路由分发：图片生成 / 闲聊（天气由 LLM function calling 处理）
            // 图片生成保留专用流程，其余文本由 LLM 决定是否调用工具。
            // 根据识别结果路由：图片生成 或 LLM 对话

            if (isImageGenRequest(recognizedText)) {
                handleImageGeneration(fromUser, recognizedText);
            } else {
                String llmReply = llmService.chat(fromUser, recognizedText);
                handleLlmReply(fromUser, llmReply);
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

    /** 处理文件消息：下载 → 提取文本 → LLM 摘要。 */
    private void handleFileMessage(String fromUser, MessageItem item, FileItem fileItem) {
        try {
            client.sendTextWithTyping(fromUser, "正在查看文件，请稍候...", 500);
            byte[] fileBytes = client.downloadFileFromMessageItem(item);
            String summary = fileSummaryService.summarizeFile(fileBytes, fileItem.getFile_name());
            sendReply(fromUser, summary);
        } catch (Exception e) {
            log.error("文件处理失败", e);
            sendReply(fromUser, "抱歉，文件处理失败，请稍后再试。");
        }
    }

    /** 通过关键词匹配检测是否为音色相关命令。 */
    private boolean isVoiceCommand(String text) {
        // 精确命令
        if (text.startsWith("切换音色") || text.startsWith("设置音色")
                || text.startsWith("换成音色") || text.startsWith("更换音色")
                || text.equals("音色列表") || text.equals("有哪些音色")
                || text.equals("当前音色") || text.equals("我的音色")
                || text.startsWith("音色")) {
            return true;
        }
        // 自然语言音色请求：必须同时包含"声音关键词"和"动作关键词"
        String[] voiceKeys = {"声音", "语音", "音色", "声线", "嗓音"};
        String[] actionKeys = {"切换", "换", "设置", "改", "想要", "换一个", "换个",
                "变成", "改成", "有没有", "换个"};

        boolean hasVoice = false;
        for (String kw : voiceKeys) {
            if (text.contains(kw)) { hasVoice = true; break; }
        }
        if (!hasVoice) return false;

        for (String kw : actionKeys) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    /** 处理音色命令：列表查询、当前音色、切换音色。 */
    private void handleVoiceCommand(String fromUser, String text) {
        if (text.equals("音色列表") || text.equals("有哪些音色")) {
            sendReply(fromUser, speechService.getAvailableVoices());
        } else if (text.equals("当前音色") || text.equals("我的音色")) {
            sendReply(fromUser, "当前音色：" + speechService.getCurrentVoice(fromUser));
        } else {
            // 提取音色名称并切换："切换音色Cherry" → "Cherry"
            String voiceName = text.replaceFirst("^(切换音色|设置音色|换成音色|更换音色|音色)", "").trim();
            sendReply(fromUser, speechService.setVoice(fromUser, voiceName));
        }
    }

    /** 应用关闭时停止轮询并关闭微信连接。 */
    @PreDestroy
    public void destroy() {
        running = false;
        if (client != null) {
            client.close();
            log.info("ClawBot 已关闭");
        }
    }
}
