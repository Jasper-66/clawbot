package com.example.clawbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.Media;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** 大语言模型（LLM）对话服务 — 集成长期记忆与 Spring AI Function Calling。 */
@Slf4j
@Service
public class LlmService {

    private final OpenAiChatModel deepSeekChatModel;
    private final OpenAiChatModel dashScopeChatModel;
    private final ConversationMemoryService memoryService;
    private final List<FunctionCallback> functionCallbacks;
    private final com.example.clawbot.tool.TextToSpeechTool textToSpeechTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_TOOL_ROUNDS = 30;
    private static final int COMPRESSION_BATCH = 16;

    @Value("${deepseek.api.model}")
    private String model;

    @Value("${vision.api.model}")
    private String visionModel;

    public LlmService(
            OpenAiChatModel deepSeekChatModel,
            @Qualifier("dashScopeChatModel") OpenAiChatModel dashScopeChatModel,
            ConversationMemoryService memoryService,
            List<FunctionCallback> functionCallbacks,
            com.example.clawbot.tool.TextToSpeechTool textToSpeechTool) {
        this.deepSeekChatModel = deepSeekChatModel;
        this.dashScopeChatModel = dashScopeChatModel;
        this.memoryService = memoryService;
        this.functionCallbacks = functionCallbacks;
        this.textToSpeechTool = textToSpeechTool;
    }

    public static final String SYSTEM_PROMPT =
            "你是一个友好的微信助手，请用简洁、自然的中文回答用户的问题。回答尽量控制在200字以内。";

    private static final String VOICE_SYSTEM_PROMPT =
            "你是一个语音助手，你的回复将被直接朗读给用户听。重要规则："
                    + "1. 直接输出要朗读的内容，绝对不要加任何开场白或客套话"
                    + "2. 不要使用emoji、表情符号和特殊字符"
                    + "3. 不要解释你正在做什么，直接给结果";

    /** 文本对话：从长期记忆获取上下文 → LLM 推理 → 追加消息到记忆 → 必要时压缩历史 */
    public String chat(String userId, String userMessage) {
        // "语音xxx" → 剥离前缀，LLM 生成文字后再主动调 TTS
        boolean wantsTts = isVoiceRequest(userMessage);
        String actualMessage = wantsTts ? stripVoicePrefix(userMessage) : userMessage;
        log.info("chat: wantsTts={}, actualMessage={}", wantsTts, actualMessage);

        List<Message> messages = new ArrayList<>();

        if (wantsTts) {
            // 语音请求：独立 prompt，不加载历史上下文（避免旧对话干扰输出风格）
            messages.add(new SystemMessage(VOICE_SYSTEM_PROMPT));
        } else {
            messages.add(new SystemMessage(SYSTEM_PROMPT));
            List<Map<String, Object>> contextMessages = memoryService.getContext(userId);
            appendContextMessages(messages, contextMessages);
        }
        messages.add(new UserMessage(actualMessage));

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.7)
                .maxTokens(1024)
                .toolCallbacks(functionCallbacks)
                .toolChoice("auto")
                .build();

        String[] ttsFilePathHolder = new String[1];
        String textReply = callWithTools(messages, options, ttsFilePathHolder);

        String audioPath = ttsFilePathHolder[0];

        // 用户请求语音时，对 LLM 文字回复主动调用 TTS
        if (wantsTts && audioPath == null) {
            audioPath = invokeTts(textReply);
        }

        // 存入记忆（纯文本，不带 [audio:...] 标记，避免 LLM 学会模仿并虚构文件路径）
        memoryService.appendMessage(userId, "user", userMessage);
        memoryService.appendMessage(userId, "assistant", textReply);

        if (memoryService.needsCompression(userId)) {
            compressHistoryAsync(userId);
        }

        // 语音请求：只返回 [audio:path]，不附带文字
        if (audioPath != null) {
            return wantsTts ? "[audio:" + audioPath + "]" : "[audio:" + audioPath + "]" + textReply;
        }

        return textReply.trim();
    }

    private static final String[] VOICE_REQUEST_PREFIXES = {
        "用语音", "语音回答", "语音告诉我", "语音说下", "语音讲", "发语音", "语音"
    };

    private boolean isVoiceRequest(String msg) {
        if (msg == null) return false;
        for (String prefix : VOICE_REQUEST_PREFIXES) {
            if (msg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String stripVoicePrefix(String msg) {
        for (String prefix : VOICE_REQUEST_PREFIXES) {
            if (msg.startsWith(prefix)) {
                String content = msg.substring(prefix.length()).trim();
                return content.isEmpty() ? msg : content;
            }
        }
        return msg;
    }

    /** 对文本内容直接调用 TTS 工具生成语音文件，返回文件路径。 */
    private String invokeTts(String text) {
        try {
            log.info("主动调用 TTS: textLength={}", text.length());
            String result = textToSpeechTool.textToSpeech(text);
            JsonNode node = objectMapper.readTree(result);
            return node.path("file_path").asText(null);
        } catch (Exception e) {
            log.warn("主动 TTS 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String callWithTools(List<Message> messages, OpenAiChatOptions options,
                                  String[] ttsFilePathOut) {
        try {
            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                Prompt prompt = new Prompt(messages, options);
                ChatResponse response = deepSeekChatModel.call(prompt);
                AssistantMessage assistantMsg = response.getResult().getOutput();

                List<ToolCall> toolCalls = assistantMsg.getToolCalls();
                if (toolCalls == null || toolCalls.isEmpty()) {
                    String content = assistantMsg.getText();
                    return (content == null || content.isBlank())
                            ? "抱歉，我没有生成有效回复，请稍后再试。" : content;
                }

                if (round == MAX_TOOL_ROUNDS) {
                    return "抱歉，工具调用次数过多，请换一种方式提问。";
                }

                messages.add(assistantMsg);

                List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
                for (ToolCall toolCall : toolCalls) {
                    String toolName = toolCall.name();
                    String toolResult;
                    if ("text_to_speech".equals(toolName)) {
                        toolResult = executeTtsCallback(toolCall.arguments(), ttsFilePathOut);
                        log.info("执行工具(TTS): name={}, id={}", toolName, toolCall.id());
                    } else {
                        toolResult = executeCallback(toolName, toolCall.arguments());
                        log.info("执行工具: name={}, id={}", toolName, toolCall.id());
                    }
                    toolResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolName, toolResult));
                }

                messages.add(new ToolResponseMessage(toolResponses, Map.of()));
            }
            return "抱歉，我暂时无法处理，请稍后再试。";
        } catch (Exception e) {
            log.error("Function Calling 调用失败", e);
            return "抱歉，我暂时无法处理，请稍后再试。";
        }
    }

    /** 直接调用 TTS 工具（绕过 ToolCallback 避免返回值被二次 JSON 编码）。 */
    private String executeTtsCallback(String arguments, String[] ttsFilePathOut) {
        try {
            JsonNode argNode = objectMapper.readTree(arguments);
            String text = argNode.path("text").asText("");
            String result = textToSpeechTool.textToSpeech(text);
            JsonNode resultNode = objectMapper.readTree(result);
            String path = resultNode.path("file_path").asText(null);
            if (path != null) {
                ttsFilePathOut[0] = path;
            }
            return result;
        } catch (Exception e) {
            log.warn("TTS 工具执行失败: {}", e.getMessage());
            return "{\"error\":\"TTS 执行失败\"}";
        }
    }

    private String executeCallback(String functionName, String arguments) {
        for (FunctionCallback fc : functionCallbacks) {
            if (fc.getName().equals(functionName)) {
                return fc.call(arguments);
            }
        }
        return "工具调用失败：未找到工具 " + functionName;
    }

    private void compressHistoryAsync(String userId) {
        try {
            List<Map<String, Object>> oldMessages = memoryService.extractForCompression(userId, COMPRESSION_BATCH);
            if (oldMessages.isEmpty()) return;

            String summary = summarizeMessages(oldMessages);
            if (summary != null && !summary.isBlank()) {
                memoryService.appendLongTermMemory(userId, summary);
            }
        } catch (Exception e) {
            log.warn("压缩对话历史失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    private String summarizeMessages(List<Map<String, Object>> messages) {
        StringBuilder transcript = new StringBuilder();
        for (Map<String, Object> msg : messages) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            String content = String.valueOf(msg.getOrDefault("content", ""));
            transcript.append(role).append(": ").append(content).append("\n");
        }

        String promptText = "请用一段简洁的中文（不超过150字）总结以下对话的核心内容和关键信息。只输出摘要文本，不要加任何前缀：\n\n" + transcript;

        List<Message> msgs = List.of(
                new SystemMessage("你是一个对话摘要助手，请用简洁的中文提取对话中的关键信息。"),
                new UserMessage(promptText)
        );

        Prompt p = new Prompt(msgs, OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.3)
                .maxTokens(256)
                .build());

        try {
            ChatResponse response = deepSeekChatModel.call(p);
            String summary = response.getResult().getOutput().getText();
            return (summary != null && !summary.isBlank()) ? summary.trim() : null;
        } catch (Exception e) {
            log.warn("生成对话摘要失败: {}", e.getMessage());
            return null;
        }
    }

    // ─── 图片识别 ──────────────────────────────────────────

    public String chatWithImage(String userId, byte[] imageBytes, String fileName) {
        String mimeType = getMimeType(fileName);

        var userMessage = new UserMessage(
                "请详细描述这张图片的内容。用友好、简洁的中文回复，控制在200字以内。",
                List.of(new Media(MimeTypeUtils.parseMimeType(mimeType),
                        new ByteArrayResource(imageBytes))));

        List<Map<String, Object>> contextMessages = memoryService.getContext(userId);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        appendContextMessages(messages, contextMessages);
        messages.add(userMessage);

        Prompt prompt = new Prompt(messages, OpenAiChatOptions.builder()
                .model(visionModel)
                .maxTokens(1024)
                .build());

        String reply = callVision(prompt);

        memoryService.appendMessage(userId, "user", "[发送了一张图片]");
        memoryService.appendMessage(userId, "assistant", reply);

        if (memoryService.needsCompression(userId)) {
            compressHistoryAsync(userId);
        }

        return reply;
    }

    private void appendContextMessages(List<Message> messages, List<Map<String, Object>> contextMessages) {
        for (Map<String, Object> ctxMsg : contextMessages) {
            String role = String.valueOf(ctxMsg.getOrDefault("role", ""));
            String content = String.valueOf(ctxMsg.getOrDefault("content", ""));
            if ("system".equals(role)) {
                messages.add(new SystemMessage(content));
            } else if ("assistant".equals(role)) {
                messages.add(new AssistantMessage(content));
            } else {
                messages.add(new UserMessage(content));
            }
        }
    }

    private String callVision(Prompt prompt) {
        try {
            ChatResponse response = dashScopeChatModel.call(prompt);
            String content = response.getResult().getOutput().getText();
            log.info("Vision 回复: {}", content);
            return (content != null && !content.isBlank()) ? content.trim()
                    : "抱歉，我暂时无法处理，请稍后再试。";
        } catch (Exception e) {
            log.error("Vision 调用失败: {}", e.getMessage());
            return "抱歉，AI 服务暂时不可用。";
        }
    }

    private String getMimeType(String fileName) {
        if (fileName == null) return "image/png";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }
}
