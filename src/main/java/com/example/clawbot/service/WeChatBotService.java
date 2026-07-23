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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信机器人核心服务 — 系统的"神经中枢"。
 *
 * <p>作为消息总线，负责微信登录、消息轮询、消息路由和回复发送。
 * 根据消息类型（文本、图片、语音、文件）将消息分发到对应的业务服务处理。</p>
 *
 * <h3>完整消息处理流程</h3>
 * <pre>
 * 微信服务器
 *   ↓ ILinkClient.getUpdates()（每 2 秒轮询）
 * 消息去重（processedMsgIds Set）
 *   ↓
 * WeChatBotService.handleMessage()
 *   ├── 文本消息（TextItem）
 *   │     ├── 音色切换命令 → SpeechService.setVoice() / getAvailableVoices()
 *   │     ├── TTS 请求（朗读/语音说...）→ SpeechService.textToSpeech() → 发送 .wav
 *   │     ├── 图片生成请求（生成图片/画一个...）→ ImageGenerationService.generateImage()
 *   │     └── 其他文本 → LlmService.chat() → handleLlmReply()（检测 [audio:...] 标记）
 *   ├── 图片消息（ImageItem）→ 下载图片 → LlmService.chatWithImage()
 *   ├── 语音消息（VoiceItem）→ 下载语音 → SpeechService.speechToText() → 识别文本 → 递归路由为文本
 *   └── 文件消息（FileItem）→ 下载文件 → FileSummaryService.summarizeFile()
 * </pre>
 *
 * <h3>关键词优先级</h3>
 * <p>文本消息中各关键词检测的顺序决定了实际处理行为：</p>
 * <ol>
 *   <li>音色命令（最高优先级）— "切换音色"、"音色列表" 等</li>
 *   <li>TTS 请求 — "朗读"、"读一下"、"语音说" 等前缀</li>
 *   <li>图片生成 — "生成图片"、"画一个" 等关键词</li>
 *   <li>LLM 对话（兜底）— 以上均不匹配的文本</li>
 * </ol>
 *
 * <h3>线程模型</h3>
 * <p>消息轮询在单个守护线程中运行（通过 {@link CompletableFuture#runAsync} 启动），
 * 每条消息同步处理。对于耗时操作（如图片生成、文件总结），不额外创建线程 —
 * 消息处理期间轮询线程阻塞，意味着同时间段的新消息会在队列中等待。</p>
 *
 * @see com.example.clawbot.service.LlmService
 * @see com.example.clawbot.service.SpeechService
 * @see com.example.clawbot.service.FileSummaryService
 * @see com.example.clawbot.service.ImageGenerationService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeChatBotService {

    /** LLM 对话服务 — 处理文本聊天和图片识别 */
    private final LlmService llmService;

    /** 图片生成服务 — 根据文本描述通过 AI 生成图片 */
    private final ImageGenerationService imageGenerationService;

    /** 语音服务 — TTS（文字→语音）和 ASR（语音→文字） */
    private final SpeechService speechService;

    /** 文件总结服务 — 提取文件内容并通过 LLM 生成摘要 */
    private final FileSummaryService fileSummaryService;
    private final ReminderService reminderService;

    /** ILink 微信客户端 — 底层 SDK，负责与微信服务器通信 */
    private ILinkClient client;

    /** 轮询运行标志，{@link #destroy()} 中设为 false 以停止轮询循环 */
    private volatile boolean running = true;
    private volatile boolean loggedIn = false;

    /**
     * 已处理消息 ID 集合，用于消息去重。
     *
     * <p>使用 {@link ConcurrentHashMap#newKeySet()} 创建线程安全集合。
     * 微信可能在网络重试时推送重复消息，通过 Set 的 add 方法（返回 false 表示已存在）
     * 确保每条消息只处理一次。集合大小超过 500 时自动清空，防止内存泄漏。</p>
     */
    private final Set<Long> processedMsgIds = ConcurrentHashMap.newKeySet();

    /**
     * 应用启动钩子 — Spring 容器初始化后自动调用。
     *
     * <p>通过 {@link PostConstruct} 注解触发。采用异步方式（{@link CompletableFuture#runAsync}）
     * 启动微信登录流程，避免阻塞 Spring 启动线程。
     * 如果登录失败（如网络不通、二维码过期），错误仅记录日志，不影响应用其他功能。</p>
     */
    @PostConstruct
    public void init() {
        CompletableFuture.runAsync(this::startBot);
    }

    /**
     * 构建 ILink 客户端并执行微信登录流程。
     *
     * <p>完整流程：</p>
     * <ol>
     *   <li>构建 ILinkClient（配置超时 35s、HTTP 重试 3 次、心跳启用）</li>
     *   <li>调用 {@code client.executeLogin()} 获取登录二维码（文本形式）</li>
     *   <li>将二维码输出到日志，由运维人员扫码授权</li>
     *   <li>调用 {@code client.getLoginFuture().get()} 阻塞等待扫码完成</li>
     *   <li>登录成功后立即启动消息轮询</li>
     * </ol>
     *
     * <p>登录过程是阻塞的 — 如果无人扫码，应用将一直停留在登录等待状态。
     * 这在开发/测试环境是合理的，生产环境建议增加超时和重登录机制。</p>
     */
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

            // 开始轮询消息
            pollMessages();

        } catch (Exception e) {
            loggedIn = false;
            log.error("ClawBot 启动失败", e);
        }
    }

    /**
     * 消息轮询主循环 — 每 2 秒拉取一次微信消息。
     *
     * <p>循环体：</p>
     * <ol>
     *   <li>调用 {@code client.getUpdates()} 拉取新消息列表</li>
     *   <li>遍历每条消息：去重检查 → 分发到 {@link #handleMessage}</li>
     *   <li>去重集合超过 500 条时清空（简单策略，防止无限增长）</li>
     *   <li>{@code Thread.sleep(2000)} 等待 2 秒后下一轮</li>
     * </ol>
     *
     * <p><b>退出条件</b>：{@code running == false}（应用关闭时由 {@link #destroy()} 设置）
     * 或当前线程被中断。</p>
     *
     * <p><b>去重策略</b>：微信可能因网络问题重发消息，使用 message_id 去重。
     * 集合上限 500 是简单方案 — 超过后全量清空意味着旧的重复消息可能被再次处理，
     * 但对用户体验影响极小（微信重发窗口远小于 500 条消息的到达时间）。</p>
     */
    private void pollMessages() {
        while (running) {
            try {
                // 主动向微信服务器发起 HTTP 请求，获取最新消息列表
                List<WeixinMessage> messages = client.getUpdates();
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

            // 轮询间隔：2 秒
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 消息分发处理器 — 根据消息子项类型路由到对应处理方法。
     *
     * <p>一个 WeixinMessage 可能包含多个 Item（图文混排消息），
     * 每个 Item 按其类型（文本/图片/语音/文件）分别处理。</p>
     *
     * <p><b>文本消息关键词优先级</b>（按检查顺序）：</p>
     * <ol>
     *   <li>音色命令（"切换音色"、"音色列表" 等）</li>
     *   <li>TTS 请求（"朗读"、"读一下"、"语音说" 等）</li>
     *   <li>图片生成请求（"生成图片"、"画一个" 等）</li>
     *   <li>LLM 对话（以上均不匹配的文本）</li>
     * </ol>
     *
     * @param msg 微信消息对象（包含发送者 ID 和消息子项列表）
     */
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

    /**
     * 发送文本回复，带有"正在输入..."状态模拟。
     *
     * <p>调用 {@code client.sendTextWithTyping} 在发送文本前先显示
     * "正在输入..."状态 1500 毫秒，使机器人回复更拟人化。</p>
     *
     * <p>发送异常被静默吞掉（仅记录日志），因为消息发送失败不应导致
     * 消息处理流程中断 — 后续消息仍需继续处理。</p>
     *
     * @param toUser 目标用户 ID（微信的 to_user_id）
     * @param reply  回复文本内容
     */
    private void sendReply(String toUser, String reply) {
        try {
            client.sendTextWithTyping(toUser, reply, 1500);
        } catch (Exception e) {
            log.error("发送消息失败", e);
        }
    }

    /**
     * 处理 LLM 回复 — 识别并发送内嵌的语音标记。
     *
     * <p>LLM 可能在回复中嵌入 {@code [audio:/path/to/file.wav]} 标记
     * （由 TextToSpeechTool 生成）。此方法检测标记并：</p>
     * <ol>
     *   <li>读取标记指向的 WAV 文件</li>
     *   <li>通过 ILink SDK 发送语音消息</li>
     *   <li>移除标记后，将剩余文字作为文本发送</li>
     *   <li>发送成功后删除临时音频文件</li>
     * </ol>
     *
     * <p>如果没有音频标记，则作为普通文本消息发送。</p>
     *
     * @param fromUser 目标用户 ID
     * @param reply    LLM 返回的完整回复文本（可能包含 [audio:...] 标记）
     */
    private void handleLlmReply(String fromUser, String reply) {
        if (reply == null || reply.isEmpty()) {
            return;
        }
        // 使用正则匹配 [audio:文件路径] 标记
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\[audio:(.+?)]")
                .matcher(reply);
        if (matcher.find()) {
            String audioPath = matcher.group(1);
            java.io.File audioFile = new java.io.File(audioPath);
            if (audioFile.exists() && audioFile.isFile()) {
                try {
                    // 读取音频文件并发送语音消息
                    byte[] audioBytes = java.nio.file.Files.readAllBytes(audioFile.toPath());
                    client.sendFile(fromUser, audioBytes, "语音回复.wav", "");
                    log.info("LLM 触发的语音已发送: path={}, size={} bytes", audioPath, audioBytes.length);

                    // 移除音频标记，将剩余文本作为文字消息发送
                    String remaining = reply.replace(matcher.group(), "").trim();
                    if (!remaining.isEmpty()) {
                        sendReply(fromUser, remaining);
                    }

                    // 清理临时文件
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
        }
        // 无音频标记或发送失败 → 作为普通文本发送
        sendReply(fromUser, reply);
    }

    /**
     * 判断用户文本是否为图片生成请求。
     *
     * <p>通过关键词匹配识别用户意图，支持多种同义表达。
     * 匹配策略：先检查已知的完整短语，再检查组合条件。</p>
     *
     * <p>触发词包括：生成图片/图像/一张/个图、画一个/一张/个/只/幅、
     * 帮我画、做个图/一张图、来一张/张 等。</p>
     *
     * @param text 用户输入文本
     * @return true 如果匹配图片生成请求模式
     */
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

    /**
     * 处理图片生成请求。
     *
     * <p>流程：发送"正在生成"提示 → 调用 AI 生成图片 → 将图片发送给用户。
     * 生成失败时发送错误提示文本。</p>
     *
     * @param fromUser 请求用户 ID
     * @param text     用户原始消息（从中提取图生文 prompt）
     */
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

    /**
     * 从用户消息中提取图片生成提示词。
     *
     * <p>移除所有触发关键词（如"生成图片"、"画一个"等），保留用户真正想要
     * 生成的描述内容。如果移除后为空（用户只发了关键词），则使用原始文本。</p>
     *
     * <p>注意：正则替换可能过度清理（如"画一只猫生成图片"），
     * 但对大多数正常输入效果良好。</p>
     *
     * @param text 用户原始消息
     * @return 提取后的图片生成提示词
     */
    private String extractImagePrompt(String text) {
        String prompt = text
                .replaceAll("生成图片|生成图像|生成一张|生成个图|画一个|画一张|画个|画一只|画只|画幅|帮我画|图片生成|图像生成|做一张图|做个图|来一张|来张|生成|图片|图像|照片|图", "")
                .trim();
        return prompt.isEmpty() ? text : prompt;
    }

    /**
     * 判断用户文本是否为 TTS（文字转语音）请求。
     *
     * <p>通过前缀匹配识别：以"朗读"、"读一下"、"语音说"、"语音播报"、
     * "转语音"、"语音回复"开头的消息视为 TTS 请求。</p>
     *
     * @param text 用户输入文本
     * @return true 如果是 TTS 请求
     */
    private boolean isTtsRequest(String text) {
        return text.startsWith("朗读") || text.startsWith("读一下")
                || text.startsWith("语音说") || text.startsWith("语音播报")
                || text.startsWith("转语音") || text.startsWith("语音回复");
    }

    /**
     * 从 TTS 请求中提取需要朗读的文本内容。
     *
     * <p>移除前缀关键词（"朗读"、"读一下"等），返回剩余文本。
     * 使用正则 {@code replaceFirst} 确保只移除第一个匹配的前缀。</p>
     *
     * @param text 用户原始消息（如 "朗读今天天气真好"）
     * @return 去除 TTS 前缀后的待朗读文本（如 "今天天气真好"）
     */
    private String extractTtsText(String text) {
        return text.replaceFirst("^(朗读|读一下|语音说|语音播报|转语音|语音回复)", "").trim();
    }

    /**
     * 处理 TTS 请求：文字 → 语音 → 发送。
     *
     * <p>流程：发送"正在生成"提示 → 调用 SpeechService TTS API 生成 WAV 音频
     * → 通过 ILink SDK 发送语音文件。</p>
     *
     * @param fromUser   请求用户 ID
     * @param textToRead 需要朗读的文本（已去除前缀）
     */
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

    /**
     * 处理语音消息 — 下载 → ASR 识别 → 路由识别文本。
     *
     * <p>完整流程：</p>
     * <ol>
     *   <li>下载语音文件（通过 ILink SDK）</li>
     *   <li>根据微信编码类型判断文件格式：
     *     <ul>
     *       <li>encode_type=4 → SILK（需要转码）</li>
     *       <li>encode_type=0 → WAV（无需转码）</li>
     *       <li>其他 → 默认为 AMR</li>
     *     </ul>
     *   </li>
     *   <li>调用 ASR 服务将语音转为文本</li>
     *   <li>对识别文本判断是否为图片生成请求，相应路由</li>
     *   <li>不是图片生成则进入 LLM 对话</li>
     * </ol>
     *
     * @param fromUser 发送用户 ID
     * @param item     消息子项（包含语音元数据）
     */
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

    /**
     * 处理文件消息 — 下载 → 提取文本 → LLM 摘要。
     *
     * <p>支持 PDF、DOCX、XLSX、PPTX、TXT 等常见办公文档格式。
     * 文件先由 WeChatBotService 下载，再委托给 FileSummaryService 处理。</p>
     *
     * @param fromUser 发送用户 ID
     * @param item     消息子项（包含文件二进制数据）
     * @param fileItem 文件元信息（文件名等）
     */
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

    /**
     * 检测用户文本是否为音色相关命令。
     *
     * <p>触发条件（满足之一即视为音色命令）：</p>
     * <ul>
     *   <li>自然切换表达，如 "帮我换个声音"、"把声音换成Ethan"</li>
     *   <li>指定音色表达，如 "使用Cherry音色"、"我想用芊悦的声音"</li>
     *   <li>列表和查询表达，如 "有哪些声音"、"现在是什么音色"</li>
     * </ul>
     *
     * @param text 用户输入文本
     * @return true 如果是音色相关命令
     */
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

    /**
     * 处理音色相关命令。
     *
     * <p>支持的命令：</p>
     * <ul>
     *   <li>"声音列表" / "支持哪些音色" → 列出所有 15 种音色及描述</li>
     *   <li>"当前音色" / "现在是什么声音" → 显示当前使用的音色</li>
     *   <li>"把声音换成Ethan" / "我想用芊悦的声音" → 切换音色</li>
     *   <li>"帮我换个声音" → 未指定目标时先展示可选音色</li>
     * </ul>
     *
     * @param fromUser 请求用户 ID
     * @param text     用户原始消息
     */
    private void handleVoiceCommand(String fromUser, String text) {
        String command = stripVoiceCommandPoliteness(text);
        if (isVoiceListRequest(command)) {
            sendReply(fromUser, speechService.getAvailableVoices());
        } else if (isCurrentVoiceRequest(command)) {
            sendReply(fromUser, "当前音色：" + speechService.getCurrentVoice(fromUser));
        } else {
            String voiceName = extractVoiceName(command);
            sendReply(fromUser, speechService.setVoice(fromUser, voiceName));
        }
    }

    /**
     * 判断用户是否正在询问可选音色。
     */
    private static boolean isVoiceListRequest(String text) {
        return text.equals("音色列表") || text.equals("声音列表")
                || text.equals("有哪些音色") || text.equals("有哪些声音")
                || text.matches(".*(有哪些|有什么|支持哪些|可选哪些|可以用哪些).*(音色|声音).*")
                || text.matches(".*(音色|声音).*(列表|有哪些|有什么|可选).*");
    }

    /**
     * 判断用户是否正在查询当前音色。
     */
    private static boolean isCurrentVoiceRequest(String text) {
        return text.equals("当前音色") || text.equals("我的音色")
                || text.equals("当前声音") || text.equals("我的声音")
                || text.matches(".*(当前|现在|正在|我的).*(音色|声音).*(什么|哪个|哪种).*")
                || text.matches(".*(当前|现在|正在|我的).*(什么|哪个|哪种).*(音色|声音).*");
    }

    /**
     * 从多种自然语言表达中提取音色名称。没有指定具体音色时返回空字符串。
     */
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

    /**
     * 去掉不影响指令含义的礼貌用语，便于后续统一解析。
     */
    private static String stripVoiceCommandPoliteness(String text) {
        return text.trim()
                .replaceFirst("^(请帮我|麻烦帮我|帮我|麻烦|我想|我要|给我|请)\\s*", "")
                .trim();
    }

    /**
     * 每 10 秒检查一次到期提醒。发送失败的任务会留在内存中，下一轮继续尝试。
     */
    @Scheduled(fixedDelayString = "${reminder.check-interval-ms:10000}")
    public void sendDueReminders() {
        if (!running || !loggedIn || client == null) {
            return;
        }

        for (ReminderService.ReminderTask task : reminderService.getDueReminders()) {
            try {
                String reminderText = "⏰ 提醒：" + task.content();
                if (task.type() == ReminderService.ReminderType.TEXT
                        || task.type() == ReminderService.ReminderType.BOTH) {
                    client.sendTextWithTyping(task.userId(), reminderText, 500);
                }
                if (task.type() == ReminderService.ReminderType.VOICE
                        || task.type() == ReminderService.ReminderType.BOTH) {
                    byte[] audioData = speechService.textToSpeech(task.userId(), reminderText);
                    client.sendFile(task.userId(), audioData, "定时提醒.wav", "");
                }

                reminderService.markSent(task.id());
                log.info("提醒已发送: id={}, userId={}, type={}",
                        task.id(), task.userId(), task.type());
            } catch (Exception e) {
                log.error("发送提醒失败: id={}, userId={}", task.id(), task.userId(), e);
            }
        }
    }

    /**
     * 应用关闭钩子 — Spring 容器销毁前自动调用。
     *
     * <p>通过 {@link PreDestroy} 注解触发。执行以下清理操作：</p>
     * <ol>
     *   <li>设置 {@code running = false} 通知轮询线程退出循环</li>
     *   <li>调用 {@code client.close()} 关闭与微信服务器的长连接</li>
     *   <li>释放网络资源（TCP 连接、心跳线程等）</li>
     * </ol>
     *
     * <p>注意：如果轮询线程正在 {@code Thread.sleep(2000)} 中，最多需要 2 秒
     * 才能检测到 {@code running} 变更并退出。</p>
     */
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
