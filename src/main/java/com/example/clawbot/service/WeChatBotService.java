package com.example.clawbot.service;

import com.example.clawbot.resume.model.UserProfile;
import com.example.clawbot.resume.service.ResumeOrchestrator;
import com.example.clawbot.resume.service.ResumeParser;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

// 微信机器人核心服务：登录、消息轮询、类型路由、回复发送的中枢
@Slf4j
@Service
@RequiredArgsConstructor
public class WeChatBotService {

    private final LlmService llmService;

    private final ImageGenerationService imageGenerationService;

    private final SpeechService speechService;

    private final FileSummaryService fileSummaryService;
    private final ReminderService reminderService;
    private final com.example.clawbot.tool.ReminderTool reminderTool;

    @Lazy
    private final ResumeParser resumeParser;
    @Lazy
    private final ResumeOrchestrator resumeOrchestrator;

    private ILinkClient client;

    private volatile boolean running = true;
    private volatile boolean loggedIn = false;

    private final Set<Long> processedMsgIds = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void init() {
        CompletableFuture.runAsync(this::startBot);
    }

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
            loggedIn = true;
            log.info("ClawBot 登录成功，botId={}", client.getLoginContext().getBotId());

            // ── 重启恢复：扫描数据库中的提醒任务，补偿遗漏 ──
            recoverReminders();

            // 开始轮询消息
            pollMessages();

        } catch (Exception e) {
            loggedIn = false;
            log.error("ClawBot 启动失败", e);
        }
    }

    private void pollMessages() {
        while (running) {
            try {
                // 主动向微信服务器发起 HTTP 请求，获取最新消息列表
                //client.getUpdates()主动向微信服务器拉取新消息
                List<WeixinMessage> messages = client.getUpdates();
                if (!messages.isEmpty()) {
                    //log.debug("[轮询] 拉取到 {} 条新消息", messages.size());
                }
                for (WeixinMessage msg : messages) {
                    Long msgId = msg.getMessage_id();
                    // 去重：add 返回 false 表示消息已处理过
                    if (msgId != null && !processedMsgIds.add(msgId)) {
                        continue;
                    }
                    handleMessage(msg);
                }
                // 防止 Set 无限增长：超过 500 条清空
                if (processedMsgIds.size() > 500) {
                    processedMsgIds.clear();
                }
            } catch (Exception e) {
                if (running) {
                    log.error("消息轮询异常", e);
                }
            }

            // 每两秒轮询一次
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void handleMessage(WeixinMessage msg) {
        String fromUser = msg.getFrom_user_id();
        if (msg.getItem_list() == null) return;

        msg.getItem_list().forEach(item -> {
            if (item.getText_item() != null) {
                // ── 文本消息 ──
                String text = item.getText_item().getText();
                String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                log.info("══════════ [思考] 收到文本消息 ══════════");
                log.info("  发信人: {}", fromUser);
                log.info("  内容: \"{}\"", preview);
                log.info("  → 按优先级匹配关键词: 音色命令 → TTS请求 → 图片生成 → 兜底LLM对话");

                try {
                    if (isVoiceCommand(text)) {
                        log.info("[行动] 匹配到「音色命令」关键词，路由到语音管理模块");
                        handleVoiceCommand(fromUser, text);
                    } else if (isTtsRequest(text)) {
                        log.info("[行动] 匹配到「TTS朗读」关键词，路由到语音合成模块");
                        handleTts(fromUser, extractTtsText(text));
                    } else if (isImageGenRequest(text)) {
                        log.info("[行动] 匹配到「图片生成」关键词，路由到AI绘图模块");
                        handleImageGeneration(fromUser, text);
                    } else if (isReminderRequest(text)) {
                        log.info("[行动] 匹配到「提醒」关键词，路由到提醒模块（LLM解析 → 调用工具）");
                        handleReminder(fromUser, text);
                    } else {
                        log.info("[行动] 未命中特殊关键词，作为通用对话路由到 LLM 服务 (DeepSeek Function Calling)");
                        String reply = llmService.chat(fromUser, text);
                        handleLlmReply(fromUser, reply);
                    }
                } catch (Exception e) {
                    log.error("[异常] 文本消息处理失败 | 用户: {} | 内容: \"{}\" | 原因: {} | 建议: 检查 LLM 服务和网络连接", fromUser, preview, e.getMessage(), e);
                    sendReply(fromUser, "抱歉，消息处理失败，请稍后再试。");
                }
            } else if (item.getImage_item() != null) {
                // ── 图片消息 ──
                log.info("══════════ [思考] 收到图片消息 ══════════");
                log.info("  发信人: {}", fromUser);
                log.info("  → 需要: 下载图片 → 调用视觉模型识别 → 生成文字回复");
                try {
                    byte[] imageBytes = client.downloadImageFromMessageItem(item);
                    log.info("[行动] 图片下载完成 ({} bytes)，发送到 DashScope 视觉模型进行内容识别", imageBytes.length);
                    String result = llmService.chatWithImage(fromUser, imageBytes, "image.jpg");
                    sendReply(fromUser, result);
                } catch (Exception e) {
                    log.error("[异常] 图片消息处理失败 | 用户: {} | 原因: {} | 建议: 检查网络和视觉API密钥", fromUser, e.getMessage(), e);
                    sendReply(fromUser, "抱歉，图片处理失败，请稍后再试。");
                }
            } else if (item.getVoice_item() != null) {
                // ── 语音消息 ──
                log.info("══════════ [思考] 收到语音消息 (时长{}s) ══════════", item.getVoice_item().getPlaytime());
                log.info("  发信人: {}", fromUser);
                log.info("  → 需要: 下载语音文件 → SILK解码 → ASR语音识别 → 文本路由");
                handleVoiceMessage(fromUser, item);
            } else if (item.getFile_item() != null) {
                // ── 文件消息 ──
                FileItem fileItem = item.getFile_item();
                log.info("══════════ [思考] 收到文件消息: {} ══════════", fileItem.getFile_name());
                log.info("  发信人: {}", fromUser);
                log.info("  → 需要: 下载文件 → 提取文本(PDF/DOCX/XLSX等) → LLM生成摘要");
                handleFileMessage(fromUser, item, fileItem);
            }
        });
    }

    private void sendReply(String toUser, String reply) {
        try {
            client.sendTextWithTyping(toUser, reply, 1500);
            log.info("[最终结果] 文本回复已发送到微信 ({}字符)", reply.length());
        } catch (Exception e) {
            log.error("[异常] 消息发送失败 | 收信人: {} | 原因: {} | 建议: 检查微信 ILink 连接状态", toUser, e.getMessage(), e);
        }
    }

    private void handleLlmReply(String fromUser, String reply) {
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
                    // 读取音频文件并发送语音消息
                    byte[] audioBytes = java.nio.file.Files.readAllBytes(audioFile.toPath());
                    client.sendFile(fromUser, audioBytes, "语音回复.wav", "");
                    log.info("[最终结果] 语音消息已发送到微信 ({} bytes)", audioBytes.length);

                    // 将剩余文本作为文字消息发送
                    if (!remainingText.isEmpty()) {
                        log.info("[最终结果] 附带文本说明已发送 ({}字符)", remainingText.length());
                        sendReply(fromUser, remainingText);
                    }

                    // 清理临时文件
                    try {
                        audioFile.delete();
                    } catch (Exception ignored) {
                    }
                    return;
                } catch (Exception e) {
                    log.error("[异常] 语音文件发送失败 | 文件: {} | 原因: {} | 建议: 检查文件是否损坏", audioPath, e.getMessage(), e);
                }
            } else {
                log.warn("[观察] 语音文件不存在 (LLM可能编造了路径): {}，降级为纯文本发送", audioPath);
            }
        }
        // 无音频标记或发送失败 → 作为普通文本发送
        String preview = reply.length() > 100 ? reply.substring(0, 100) + "..." : reply;
        log.info("[最终结果] 向用户发送纯文本回复: \"{}\"", preview);
        sendReply(fromUser, reply);
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
        // 组合条件：（生成 或 画）且 （图片 或 图像 或 照片）
        return (text.contains("生成") || text.contains("画"))
                && (text.contains("图片") || text.contains("图像") || text.contains("照片"));
    }

    private void handleImageGeneration(String fromUser, String text) {
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

    private String extractImagePrompt(String text) {
        String prompt = text
                .replaceAll("生成图片|生成图像|生成一张|生成个图|画一个|画一张|画个|画一只|画只|画幅|帮我画|图片生成|图像生成|做一张图|做个图|来一张|来张|生成|图片|图像|照片|图", "")
                .trim();
        return prompt.isEmpty() ? text : prompt;
    }

    // ═══════════════════════════════════════════════════
    // 提醒功能：关键词检测 + LLM解析 + 工具调用
    // ═══════════════════════════════════════════════════
    static boolean isReminderRequest(String text) {
        if (text == null || text.isBlank()) return false;
        return text.contains("提醒") || text.contains("闹钟") || text.contains("定时");
    }

    private void handleReminder(String fromUser, String text) {
        log.info("[行动] 提醒模块: 用户消息 \"{}\"，调用 LLM 解析时间和内容", text);
        try {
            // 用 LLM 解析用户消息，提取时间和内容
            String parsePrompt = "请从以下用户消息中提取提醒时间和提醒内容。当前时间是 "
                    + java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                    + "。用户消息: \"" + text + "\"\n\n"
                    + "请严格返回JSON格式（不要返回其他内容）:\n"
                    + "{\"content\":\"提醒内容\",\"trigger_at\":\"ISO8601时间+08:00\",\"type\":\"text\"}\n"
                    + "type可选: text/voice/both。如果没有明确时间，返回 {\"error\":\"需要明确时间\"}";

            String parseResult = llmService.chat(fromUser, parsePrompt);
            log.info("[观察] LLM 解析结果: {}", parseResult);

            // 解析 JSON
            com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(parseResult);

            if (json.has("error")) {
                sendReply(fromUser, "请告诉我具体的提醒时间，比如「5分钟后提醒我喝水」或「明天早上8点提醒我开会」");
                return;
            }

            String content = json.get("content").asText();
            String triggerAt = json.get("trigger_at").asText();
            String type = json.has("type") ? json.get("type").asText() : "text";

            // 调用 ReminderTool
            log.info("[行动] 调用 ReminderTool: content=\"{}\", triggerAt=\"{}\", type={}", content, triggerAt, type);
            String toolResult = reminderTool.createReminder(content, triggerAt, type, fromUser);
            log.info("[观察] ReminderTool 返回: {}", toolResult);

            // 解析工具返回结果
            com.fasterxml.jackson.databind.JsonNode resultJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(toolResult);
            if (resultJson.has("success") && resultJson.get("success").asBoolean()) {
                sendReply(fromUser, "✅ 提醒已设置！\n📝 " + content + "\n⏰ " + triggerAt);
            } else {
                String msg = resultJson.has("message") ? resultJson.get("message").asText() : "设置失败";
                sendReply(fromUser, "❌ 提醒设置失败: " + msg);
            }
        } catch (Exception e) {
            log.error("[异常] 提醒处理失败 | 用户: {} | 原因: {}", fromUser, e.getMessage(), e);
            sendReply(fromUser, "抱歉，提醒设置失败，请稍后再试。");
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

    private void handleTts(String fromUser, String textToRead) {
        log.info("[行动] TTS语音合成: 将 \"{}\" 发送到 DashScope TTS 引擎", textToRead.length() > 80 ? textToRead.substring(0, 80) + "..." : textToRead);
        try {
            client.sendTextWithTyping(fromUser, "正在生成语音...", 500);
            byte[] audioData = speechService.textToSpeech(fromUser, textToRead);
            log.info("[观察] DashScope TTS 合成成功，生成WAV音频 ({} bytes)", audioData.length);
            client.sendFile(fromUser, audioData, "语音播报.wav", "");
            log.info("[最终结果] 语音播报文件已发送到微信 ({} bytes)", audioData.length);
        } catch (Exception e) {
            log.error("[异常] TTS语音合成失败 | 文本: \"{}\" | 原因: {} | 建议: 检查 DashScope TTS API 密钥", textToRead, e.getMessage(), e);
            sendReply(fromUser, "语音生成失败，请稍后再试。");
        }
    }

    private void handleVoiceMessage(String fromUser, MessageItem item) {
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
                handleImageGeneration(fromUser, recognizedText);
            } else {
                log.info("  └─ 识别文本作为对话 → 路由到 LLM 服务");
                String llmReply = llmService.chat(fromUser, recognizedText);
                handleLlmReply(fromUser, llmReply);
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

    private void handleFileMessage(String fromUser, MessageItem item, FileItem fileItem) {
        String fileName = fileItem.getFile_name();
        log.info("══════════ [思考] 收到文件消息: {} ══════════", fileName);
        log.info("  发信人: {}", fromUser);

        // 判断是否是简历文件
        if (isResumeFile(fileName)) {
            log.info("  → 检测到简历文件，走简历解析流程");
            handleResumeFile(fromUser, item, fileName);
        } else {
            log.info("  → 普通文件，走文件摘要流程");
            handleNormalFile(fromUser, item, fileItem);
        }
    }

    /**
     * 判断是否是简历文件（PDF/Word格式，且文件名可能包含"简历"、"resume"等关键词）
     */
    private boolean isResumeFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();

        // 检查文件格式：支持PDF和Word
        boolean isSupportedFormat = lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".doc");

        // 检查文件名是否包含简历相关关键词
        boolean hasResumeKeyword = lower.contains("简历") || lower.contains("resume") || lower.contains("cv")
                || lower.contains("求职") || lower.contains("应聘");

        // 如果文件名包含简历关键词，或者是PDF/Word格式且文件名看起来像简历
        // 规则：文件名包含"简历"/"resume"关键词，或者文件名格式类似"姓名-职位-简历"
        return isSupportedFormat && (hasResumeKeyword || looksLikeResumeFileName(lower));
    }

    /**
     * 检查文件名是否看起来像简历文件名（如：张三简历.pdf, Java开发-李四.docx）
     */
    private boolean looksLikeResumeFileName(String fileName) {
        // 常见简历文件名模式：包含分隔符（-、_、.）的PDF/Word文件
        // 如：张三-Java开发.pdf, 李四_前端工程师.docx
        return (fileName.endsWith(".pdf") || fileName.endsWith(".docx"))
                && (fileName.contains("-") || fileName.contains("_"))
                && fileName.length() < 50; // 文件名不会太长
    }

    /**
     * 处理简历文件：下载 → 解析 → 保存 → 通知用户
     */
    private void handleResumeFile(String fromUser, MessageItem item, String fileName) {
        try {
            client.sendTextWithTyping(fromUser, "正在解析简历，请稍候...", 500);

            // 下载文件
            byte[] fileBytes = client.downloadFileFromMessageItem(item);
            log.info("  ├─ 简历文件下载完成 ({} bytes)", fileBytes.length);

            // 调用ResumeParser解析简历
            UserProfile profile = resumeParser.parseFromFile(fromUser, fileBytes, fileName);
            log.info("  ├─ [观察] 简历解析完成: 姓名={}, 期望职位={}, 期望城市={}",
                    profile.getName(), profile.getDesiredPosition(), profile.getDesiredCity());

            // 构建成功回复
            StringBuilder reply = new StringBuilder();
            reply.append("✅ 简历解析成功！\n\n");
            reply.append("📋 简历信息：\n");
            if (profile.getName() != null && !profile.getName().isEmpty()) {
                reply.append("• 姓名: ").append(profile.getName()).append("\n");
            }
            if (profile.getDesiredPosition() != null && !profile.getDesiredPosition().isEmpty()) {
                reply.append("• 期望职位: ").append(profile.getDesiredPosition()).append("\n");
            }
            if (profile.getDesiredCity() != null && !profile.getDesiredCity().isEmpty()) {
                reply.append("• 期望城市: ").append(profile.getDesiredCity()).append("\n");
            }
            if (profile.getSalaryRange() != null && !profile.getSalaryRange().isEmpty()) {
                reply.append("• 期望薪资: ").append(profile.getSalaryRange()).append("\n");
            }
            if (profile.getExperienceYears() != null && profile.getExperienceYears() > 0) {
                reply.append("• 工作经验: ").append(profile.getExperienceYears()).append("年\n");
            }
            if (profile.getEducation() != null && !profile.getEducation().isEmpty()) {
                reply.append("• 学历: ").append(profile.getEducation()).append("\n");
            }
            if (profile.getSkills() != null && !profile.getSkills().isEmpty()) {
                reply.append("• 技能: ").append(String.join(", ", profile.getSkills())).append("\n");
            }
            reply.append("\n💡 您可以说：\n");
            reply.append("• 「帮我找工作」— 搜索匹配岗位\n");
            reply.append("• 「帮我投简历」— 自动投递简历\n");
            reply.append("• 「查看投递进度」— 查看投递记录");

            sendReply(fromUser, reply.toString());
            log.info("  └─ [最终结果] 简历解析结果已发送给用户");

        } catch (Exception e) {
            log.error("[异常] 简历文件处理失败 | 文件: {} | 用户: {} | 原因: {}",
                    fileName, fromUser, e.getMessage(), e);
            sendReply(fromUser, "抱歉，简历解析失败。请确保：\n" +
                    "1. 文件是PDF或Word格式\n" +
                    "2. 文件内容清晰可读\n" +
                    "3. 文件未损坏\n\n" +
                    "请重新发送简历文件，或直接告诉我您的求职意向。");
        }
    }

    /**
     * 处理普通文件：下载 → 提取文本 → LLM生成摘要
     */
    private void handleNormalFile(String fromUser, MessageItem item, FileItem fileItem) {
        log.info("[行动] 开始处理文件: {} → 下载 → 提取文本 → LLM生成摘要", fileItem.getFile_name());
        try {
            client.sendTextWithTyping(fromUser, "正在查看文件，请稍候...", 500);
            byte[] fileBytes = client.downloadFileFromMessageItem(item);
            log.info("  ├─ 文件下载完成 ({} bytes)", fileBytes.length);
            // 调用FileSummaryService来提取内容并生成摘要
            String summary = fileSummaryService.summarizeFile(fileBytes, fileItem.getFile_name());
            log.info("  └─ [观察] 文件总结完成，生成摘要 ({}字符)", summary != null ? summary.length() : 0);
            sendReply(fromUser, summary);
        } catch (Exception e) {
            log.error("[异常] 文件处理失败 | 文件: {} | 用户: {} | 原因: {} | 建议: 检查文件格式是否支持",
                    fileItem.getFile_name(), fromUser, e.getMessage(), e);
            sendReply(fromUser, "抱歉，文件处理失败，请稍后再试。");
        }
    }

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

    private void handleVoiceCommand(String fromUser, String text) {
        String command = stripVoiceCommandPoliteness(text);
        log.info("[行动] 处理音色管理命令: \"{}\"", command);
        if (isVoiceListRequest(command)) {
            log.info("  → 查询可用音色列表 (共15种音色)");
            String result = speechService.getAvailableVoices();
            log.info("[最终结果] 音色列表已发送 ({}字符)", result.length());
            sendReply(fromUser, result);
        } else if (isCurrentVoiceRequest(command)) {
            log.info("  → 查询用户当前音色");
            sendReply(fromUser, "当前音色：" + speechService.getCurrentVoice(fromUser));
        } else {
            String voiceName = extractVoiceName(command);
            log.info("  → 切换音色: {}", voiceName.isEmpty() ? "(未指定具体音色)" : voiceName);
            sendReply(fromUser, speechService.setVoice(fromUser, voiceName));
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

    // ═══════════════════════════════════════════════════
    // 重启恢复：扫描数据库，补偿遗漏的提醒任务
    // ═══════════════════════════════════════════════════
    private void recoverReminders() {
        log.info("══════════ [恢复] 开始扫描数据库中的提醒任务 ══════════");
        try {
            List<ReminderService.ReminderTask> allPending = reminderService.getAllPendingReminders();
            Instant now = Instant.now();

            // 分离：已过期的（遗漏任务）和 未到期的（正常等待）
            List<ReminderService.ReminderTask> missed = allPending.stream()
                    .filter(t -> !t.triggerAt().isAfter(now))
                    .toList();
            List<ReminderService.ReminderTask> upcoming = allPending.stream()
                    .filter(t -> t.triggerAt().isAfter(now))
                    .toList();

            log.info("  ├─ 扫描到 {} 条待发送提醒", allPending.size());
            log.info("  ├─ 遗漏任务（已过期）: {} 条", missed.size());
            log.info("  └─ 未到期任务: {} 条", upcoming.size());

            // 补偿遗漏任务
            if (!missed.isEmpty()) {
                log.info("══════════ [补偿] 开始补发 {} 条遗漏提醒 ══════════", missed.size());
                for (ReminderService.ReminderTask task : missed) {
                    try {
                        sendReminder(task);

                        if (task.periodic()) {
                            // 周期性：更新下次触发时间，不删除
                            reminderService.reschedule(task.id());
                            log.info("  ├─ 周期性提醒已补偿并重新调度: id={}, content=\"{}\", nextTriggerAt已更新",
                                    task.id(), task.content());
                        } else {
                            // 一次性：标记已发送
                            reminderService.markSent(task.id());
                            log.info("  ├─ 一次性提醒已补偿: id={}, content=\"{}\"", task.id(), task.content());
                        }
                    } catch (Exception e) {
                        log.error("  ├─ 补偿提醒失败: id={}, userId={}", task.id(), task.userId(), e);
                    }
                }
                log.info("══════════ [补偿] 遗漏提醒补发完成 ══════════");
            }

            if (!upcoming.isEmpty()) {
                log.info("══════════ [恢复] {} 条未到期任务已恢复，等待定时触发 ══════════", upcoming.size());
                for (ReminderService.ReminderTask task : upcoming) {
                    log.info("  ├─ id={}, content=\"{}\", triggerAt={}", task.id(), task.content(), task.triggerAt());
                }
            }

            log.info("══════════ [恢复] 提醒任务恢复流程完成 ══════════");
        } catch (Exception e) {
            log.error("══════════ [恢复] 提醒任务恢复失败 ══════════", e);
        }
    }

    /**
     * 发送单条提醒（文本/语音），供 recoverReminders() 和 sendDueReminders() 共用。
     */
    private void sendReminder(ReminderService.ReminderTask task) throws Exception {
        String reminderText = "⏰ 提醒：" + task.content();
        if (task.type() == ReminderService.ReminderType.TEXT
                || task.type() == ReminderService.ReminderType.BOTH) {
            client.sendText(task.userId(), reminderText);
        }
        if (task.type() == ReminderService.ReminderType.VOICE
                || task.type() == ReminderService.ReminderType.BOTH) {
            byte[] audioData = speechService.textToSpeech(task.userId(), reminderText);
            client.sendFile(task.userId(), audioData, "定时提醒.wav", "");
        }
    }

    @Scheduled(fixedDelayString = "${reminder.check-interval-ms:10000}")
    public void sendDueReminders() {
        if (!running || !loggedIn || client == null) {
            log.warn("[定时扫描] 跳过: running={}, loggedIn={}, client={}", running, loggedIn, client != null);
            return;
        }

        List<ReminderService.ReminderTask> dueTasks = reminderService.getDueReminders();
        //log.info("[定时扫描] 检查到期提醒... 共 {} 条待发送", dueTasks.size());
        if (!dueTasks.isEmpty()) {
            //log.info("[定时扫描] 发现 {} 条到期提醒，开始发送", dueTasks.size());
        }

        for (ReminderService.ReminderTask task : dueTasks) {
            try {
                log.info("[定时扫描] 发送提醒: id={}, userId={}, content=\"{}\", triggerAt={}",
                        task.id(), task.userId(), task.content(), task.triggerAt());
                sendReminder(task);

                if (task.periodic()) {
                    reminderService.reschedule(task.id());
                    log.info("[定时扫描] 周期性提醒已发送并重新调度: id={}, interval={}s",
                            task.id(), task.intervalSeconds());
                } else {
                    reminderService.markSent(task.id());
                    log.info("[定时扫描] 一次性提醒已发送并标记完成: id={}", task.id());
                }
            } catch (Exception e) {
                log.warn("[定时扫描] 发送提醒失败（下次扫描重试）: id={}, userId={}, 原因: {}",
                        task.id(), task.userId(), e.getMessage());
            }
        }
    }

    @PreDestroy
    public void destroy() {
        running = false;
        loggedIn = false;
        if (client != null) {
            client.close();
            log.info("ClawBot 已关闭");
        }
    }
}
