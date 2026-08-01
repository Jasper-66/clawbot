package com.example.clawbot.resume.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过标准 MCP 协议调用猎聘工具。
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "resume.platform.provider", havingValue = "liepin")
public class LiepinMcpClientService {

    private final List<McpSyncClient> mcpClients;
    private final ObjectMapper objectMapper;

    public JsonNode searchJobs(Map<String, Object> arguments) {
        return callTool("user-search-job", arguments);
    }

    public JsonNode applyJob(String jobId, String jobKind) {
        if (jobKind == null || jobKind.isBlank()) {
            throw new IllegalArgumentException("猎聘岗位类型 jobKind 不能为空");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("jobId", parseJobId(jobId));
        arguments.put("jobKind", jobKind.trim());
        return callTool("user-apply-job", arguments);
    }

    public JsonNode callTool(String toolName, Map<String, Object> arguments) {
        McpSyncClient client = findClient(toolName);
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(toolName, arguments)
        );
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("猎聘 MCP 工具调用失败: " + readText(result));
        }
        return toJson(result);
    }

    private McpSyncClient findClient(String toolName) {
        return mcpClients.stream()
                .filter(client -> client.listTools().tools().stream()
                        .anyMatch(tool -> toolName.equals(tool.name())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "猎聘 MCP Server 未提供工具: " + toolName
                ));
    }

    private JsonNode toJson(McpSchema.CallToolResult result) {
        JsonNode structured = result.structuredContent() == null
                ? objectMapper.createObjectNode()
                : objectMapper.valueToTree(result.structuredContent());
        JsonNode textContent = parseText(readText(result));

        if (isEmpty(structured)) {
            return textContent;
        }
        if (isEmpty(textContent)) {
            return structured;
        }
        if (structured.isObject()) {
            ObjectNode merged = ((ObjectNode) structured).deepCopy();
            if (textContent.isObject()) {
                textContent.fields().forEachRemaining(field -> {
                    if (!merged.has(field.getKey())) {
                        merged.set(field.getKey(), field.getValue());
                    }
                });
            }
            merged.set("mcpTextContent", textContent);
            return merged;
        }

        ObjectNode combined = objectMapper.createObjectNode();
        combined.set("structuredContent", structured);
        combined.set("mcpTextContent", textContent);
        return combined;
    }

    private JsonNode parseText(String text) {
        if (text.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode().put("message", text);
        }
    }

    private boolean isEmpty(JsonNode node) {
        return node == null || node.isNull() || (node.isContainerNode() && node.size() == 0);
    }

    private String readText(McpSchema.CallToolResult result) {
        if (result.content() == null) {
            return "";
        }
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private Object parseJobId(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("猎聘岗位 ID 不能为空");
        }
        try {
            return Long.parseLong(jobId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("猎聘岗位 ID 必须是数字: " + jobId, exception);
        }
    }
}
