package com.example.mission.service;

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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeChatBotService {

    private final WeatherService weatherService;
    private final LlmService llmService;
    private final ImageGenerationService imageGenerationService;
    private final SpeechService speechService;
    private final FileSummaryService fileSummaryService;

    private ILinkClient client;
    private volatile boolean running = true;
    private final Set<Long> processedMsgIds = ConcurrentHashMap.newKeySet();

    //初始化startBot避免阻塞线程，采用异步编程
    @PostConstruct
    public void init() {
        CompletableFuture.runAsync(this::startBot);
    }

    //构建ilink客户端
    private void startBot() {
        try {
            client = ILinkClient.builder()
                    .config(ILinkConfig.builder()
                            .connectTimeoutMs(35000)
                            .readTimeoutMs(35000)
                            .httpMaxRetries(3)
                            .heartbeatEnabled(true)
                            .build())
                    .build();
           //获取登录二维码
            String qrContent = client.executeLogin();
            log.info("===== 微信机器人登录 =====");
            log.info("请扫描以下二维码登录 ClawBot：");
            log.info(qrContent);
            log.info("=========================");
            //阻塞方法，等待扫码
            client.getLoginFuture().get();
            log.info("ClawBot 登录成功，botId={}", client.getLoginContext().getBotId());

            pollMessages();

        } catch (Exception e) {
            log.error("ClawBot 启动失败", e);
        }
    }
 //消息轮询模式
    private void pollMessages() {
        while (running) {
            try {
                //主动向微信服务器发起请求，获取最新到达的消息列表。
                List<WeixinMessage> messages = client.getUpdates();
                for (WeixinMessage msg : messages) {
                    Long msgId = msg.getMessage_id();
                    if (msgId != null && !processedMsgIds.add(msgId)) {
                        continue;
                    }
                    handleMessage(msg);
                }
                // 防止 Set 无限增长，保留最近 500 条
                if (processedMsgIds.size() > 500) {
                    processedMsgIds.clear();
                }
            } catch (Exception e) {
                if (running) {
                    log.error("消息轮询异常", e);
                }
            }
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
        //forEach循环加lambda表达式判断信息是否为文本
        msg.getItem_list().forEach(item -> {
            if (item.getText_item() != null) {
                String text = item.getText_item().getText();
                log.info("收到文本 from={}, text={}", fromUser, text);

                //匹配关键词 — 音色切换优先级最高
                if (isVoiceCommand(text)) {
                    handleVoiceCommand(fromUser, text);
                } else if (isTtsRequest(text)) {
                    handleTts(fromUser, extractTtsText(text));
                } else if (text.contains("天气")) {
                    String city = extractCity(text);
                    sendReply(fromUser, weatherService.getWeather(city));
                } else if (isImageGenRequest(text)) {
                    handleImageGeneration(fromUser, text);
                } else {
                    sendReply(fromUser, llmService.chat(fromUser, text));
                }
            } else if (item.getImage_item() != null) {
                log.info("收到图片 from={}", fromUser);
                try {
                    byte[] imageBytes = client.downloadImageFromMessageItem(item);
                    sendReply(fromUser, llmService.chatWithImage(fromUser, imageBytes, "image.jpg"));
                } catch (Exception e) {
                    log.error("下载或识别图片失败", e);
                    sendReply(fromUser, "抱歉，图片处理失败，请稍后再试。");
                }
            } else if (item.getVoice_item() != null) {
                log.info("收到语音 from={}, playtime={}s",
                        fromUser, item.getVoice_item().getPlaytime());
                handleVoiceMessage(fromUser, item);
            } else if (item.getFile_item() != null) {
                FileItem fileItem = item.getFile_item();
                log.info("收到文件 from={}, fileName={}", fromUser, fileItem.getFile_name());
                handleFileMessage(fromUser, item, fileItem);
            }
        });
    }

    //拟人化优化
    private void sendReply(String toUser, String reply) {
        try {
            client.sendTextWithTyping(toUser, reply, 1500);
        } catch (Exception e) {
            log.error("发送消息失败", e);
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

    private String extractImagePrompt(String text) {
        String prompt = text
                .replaceAll("生成图片|生成图像|生成一张|生成个图|画一个|画一张|画个|画一只|画只|画幅|帮我画|图片生成|图像生成|做一张图|做个图|来一张|来张|生成|图片|图像|照片|图", "")
                .trim();
        return prompt.isEmpty() ? text : prompt;
    }

    private String extractCity(String text) {
        String city = text.replaceAll("天气", "").trim();
        //如果城市为空会默认发送 上海/北京天气
        if (city.isEmpty()) {
            city = text.contains("北京") ? "北京" : "上海";
        }
        return city;
    }
    //检查字符串是否以指定字符串开始 return true/false
    private boolean isTtsRequest(String text) {
        return text.startsWith("朗读") || text.startsWith("读一下")
                || text.startsWith("语音说") || text.startsWith("语音播报")
                || text.startsWith("转语音") || text.startsWith("语音回复");
    }

    //正则替换，将开头部分去掉
    private String extractTtsText(String text) {
        return text.replaceFirst("^(朗读|读一下|语音说|语音播报|转语音|语音回复)", "").trim();
    }

    private void handleTts(String fromUser, String textToRead) {
        try {
            //带输入态发送文本
            client.sendTextWithTyping(fromUser, "正在生成语音...", 500);
            byte[] audioData = speechService.textToSpeech(fromUser, textToRead);
            client.sendFile(fromUser, audioData, "语音播报.wav", "");
            log.info("语音文件已发送给 {}", fromUser);
        } catch (Exception e) {
            log.error("TTS 失败", e);
            sendReply(fromUser, "语音生成失败，请稍后再试。");
        }
    }

    private void handleVoiceMessage(String fromUser, MessageItem item) {
        try {
            client.sendTextWithTyping(fromUser, "正在识别语音，请稍候...", 500);

            byte[] voiceBytes = client.downloadVoiceFromMessageItem(item);
            String fileName = "voice.amr";
            Integer encodeType = item.getVoice_item().getEncode_type();
            if (encodeType != null && encodeType == 4) fileName = "voice.sil";
            else if (encodeType != null && encodeType == 0) fileName = "voice.wav";

            String recognizedText = speechService.speechToText(voiceBytes, fileName);
            log.info("语音识别结果: text=[{}], isImageGen={}, hasWeather={}",
                    recognizedText, isImageGenRequest(recognizedText), recognizedText.contains("天气"));

            // 路由分发：图片生成 / 天气 / 闲聊
            if (isImageGenRequest(recognizedText)) {
                handleImageGeneration(fromUser, recognizedText);
            } else if (recognizedText.contains("天气")) {
                String city = extractCity(recognizedText);
                String weather = weatherService.getWeather(city);
                byte[] replyAudio = speechService.textToSpeech(fromUser, weather);
                client.sendFile(fromUser, replyAudio, "天气语音.wav", "");
            } else {
                String llmReply = llmService.chat(fromUser, recognizedText);
                byte[] replyAudio = speechService.textToSpeech(fromUser, llmReply);
                client.sendFile(fromUser, replyAudio, "语音回复.wav", "");
                log.info("语音文件已发送给 {}", fromUser);
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

    // 音色切换命令检测
    private boolean isVoiceCommand(String text) {
        return text.startsWith("切换音色") || text.startsWith("设置音色")
                || text.startsWith("换成音色") || text.startsWith("更换音色")
                || text.equals("音色列表") || text.equals("有哪些音色")
                || text.equals("当前音色") || text.equals("我的音色")
                || text.startsWith("音色");
    }

    private void handleVoiceCommand(String fromUser, String text) {
        if (text.equals("音色列表") || text.equals("有哪些音色")) {
            sendReply(fromUser, speechService.getAvailableVoices());
        } else if (text.equals("当前音色") || text.equals("我的音色")) {
            sendReply(fromUser, "当前音色：" + speechService.getCurrentVoice(fromUser));
        } else {
            String voiceName = text.replaceFirst("^(切换音色|设置音色|换成音色|更换音色|音色)", "").trim();
            sendReply(fromUser, speechService.setVoice(fromUser, voiceName));
        }
    }

   //应用关闭，spring容器会自动调用所以标注了 @PreDestroy方法
    @PreDestroy
    public void destroy() {
        //轮询退出，不在接受信息
        running = false;
        if (client != null) {
            //关闭于微信服务器的长连接，释放网络资源，防止连接泄露
            client.close();
            log.info("ClawBot 已关闭");
        }
    }
}
