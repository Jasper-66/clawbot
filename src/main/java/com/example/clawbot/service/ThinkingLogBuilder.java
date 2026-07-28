package com.example.clawbot.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 思考日志构建器 — 记录机器人的完整思考过程。
 *
 * <p>在 LLM 对话、工具调用、意图识别等每个环节记录步骤，
 * 最终构建为格式化的日志文本，附带在回复中展示给用户。</p>
 */
public class ThinkingLogBuilder {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String userMessage;
    private final List<String> steps = new ArrayList<>();
    private final List<ToolCallRecord> toolCalls = new ArrayList<>();

    public ThinkingLogBuilder(String userMessage) {
        this.userMessage = userMessage;
    }

    /**
     * 记录一个普通步骤（意图分析、LLM 调用、决策等）
     */
    public ThinkingLogBuilder addStep(String emoji, String step) {
        steps.add(emoji + " " + step);
        return this;
    }

    /**
     * 记录一次工具调用（包含参数、技术说明、返回结果摘要）
     */
    public ThinkingLogBuilder addToolCall(String toolName, String args, String tech, String resultSummary) {
        toolCalls.add(new ToolCallRecord(toolName, args, tech, resultSummary));
        return this;
    }

    /**
     * 构建完整的思考日志文本
     */
    public String build(String finalReply) {
        StringBuilder sb = new StringBuilder();
        sb.append("🧠 思考过程：\n");
        sb.append("━━━━━━━━━━━━━━━━\n");

        // 收到消息
        sb.append("📥 收到消息：\"").append(truncate(userMessage, 50)).append("\"\n");

        // 普通步骤
        for (String step : steps) {
            sb.append(step).append("\n");
        }

        // 工具调用详情
        for (ToolCallRecord tc : toolCalls) {
            sb.append("🔧 执行工具：").append(tc.toolName).append("\n");
            if (tc.args != null && !tc.args.isEmpty()) {
                sb.append("   └ 参数：").append(truncate(tc.args, 80)).append("\n");
            }
            if (tc.tech != null && !tc.tech.isEmpty()) {
                sb.append("   └ 技术：").append(tc.tech).append("\n");
            }
            if (tc.resultSummary != null && !tc.resultSummary.isEmpty()) {
                sb.append("   └ 结果：").append(truncate(tc.resultSummary, 100)).append("\n");
            }
        }

        sb.append("━━━━━━━━━━━━━━━━\n");

        // 正式回复
        sb.append("\n💬 回复：\n");
        sb.append(finalReply);

        return sb.toString();
    }

    /**
     * 构建简化的思考日志（用于非 LLM 路径，如图片生成、TTS 等）
     */
    public static String buildSimple(String userMessage, String... steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("🧠 思考过程：\n");
        sb.append("━━━━━━━━━━━━━━━━\n");
        sb.append("📥 收到消息：\"").append(truncate(userMessage, 50)).append("\"\n");
        for (String step : steps) {
            sb.append(step).append("\n");
        }
        sb.append("━━━━━━━━━━━━━━━━\n");
        return sb.toString();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private static class ToolCallRecord {
        final String toolName;
        final String args;
        final String tech;
        final String resultSummary;

        ToolCallRecord(String toolName, String args, String tech, String resultSummary) {
            this.toolName = toolName;
            this.args = args;
            this.tech = tech;
            this.resultSummary = resultSummary;
        }
    }
}
