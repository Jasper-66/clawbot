package com.example.clawbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件总结服务。
 *
 * <p>支持从 PDF、DOCX、XLSX、PPTX、TXT 等格式文件中提取文本内容，
 * 并通过 LLM 生成简洁的中文摘要。</p>
 *
 * <h3>支持的文件格式</h3>
 * <ul>
 *   <li>PDF - 使用 Apache PDFBox 提取文本</li>
 *   <li>DOCX - 使用 Apache POI 提取段落文本</li>
 *   <li>XLSX - 使用 Apache POI 提取单元格内容</li>
 *   <li>PPTX - 使用 Apache POI 提取幻灯片文本</li>
 *   <li>TXT/CSV/LOG - 直接按 UTF-8/GBK 编码读取</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileSummaryService {

    /** HTTP 客户端，用于调用 LLM API */
    private final RestTemplate restTemplate;

    /** JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** DeepSeek API 密钥 */
    @Value("${deepseek.api.key}")
    private String apiKey;

    /** DeepSeek API 基础地址 */
    @Value("${deepseek.api.base-url}")
    private String baseUrl;

    /** DeepSeek 模型名称 */
    @Value("${deepseek.api.model}")
    private String model;

    /** 单次总结最大文本长度，超出则截断 */
    private static final int MAX_TEXT_LENGTH = 4000;

    /**
     * 总结文件内容（对外主入口）。
     *
     * <p>完整的处理流程：</p>
     * <ol>
     *   <li>根据文件扩展名（pdf/docx/xlsx/pptx/txt）自动选择提取引擎</li>
     *   <li>提取文本后，若超过 {@value #MAX_TEXT_LENGTH} 字符则截断并追加提示</li>
     *   <li>将预处理后的文本送入 DeepSeek LLM，请求生成 200 字以内的中文摘要</li>
     * </ol>
     *
     * <p>所有异常均在内部捕获，返回友好的错误提示文本而非抛出异常，
     * 确保调用方（WeChatBotService）可以直接将返回值发送给用户。</p>
     *
     * @param fileBytes 文件完整字节数组，由 WeChatBotService 下载后传入
     * @param fileName  原始文件名（如 "报告.pdf"），用于判断格式和提示词上下文
     * @return 文件内容摘要文本，或错误提示（如 "不支持的文件格式"）
     */
    public String summarizeFile(byte[] fileBytes, String fileName) {
        String text = extractText(fileBytes, fileName);
        if (text == null || text.isBlank()) {
            return "未能从文件中提取到文本内容，可能是不支持的格式或文件为空。";
        }

        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH) + "\n...（内容过长已截断）";
        }

        return callLlm(text, fileName);
    }

    /**
     * 根据文件扩展名路由到对应的文本提取方法。
     *
     * <p>通过文件名后缀判断文档类型，调用对应的 Apache POI / PDFBox 提取器。
     * 所有提取异常在此处被 catch 并转为可读的错误说明字符串，
     * 确保调用链不因单个文件解析失败而中断。</p>
     *
     * <h3>支持的扩展名映射</h3>
     * <ul>
     *   <li>{@code .pdf} → Apache PDFBox</li>
     *   <li>{@code .docx} → Apache POI XWPFDocument</li>
     *   <li>{@code .xlsx} → Apache POI XSSFWorkbook</li>
     *   <li>{@code .pptx} → Apache POI XMLSlideShow</li>
     *   <li>{@code .txt/.csv/.log} → 纯文本（UTF-8 / GBK 编码尝试）</li>
     * </ul>
     *
     * @param fileBytes 文件字节数组
     * @param fileName  文件名（用于提取扩展名）
     * @return 提取的纯文本内容，失败时返回错误说明（不以异常形式传播）
     */
    private String extractText(byte[] fileBytes, String fileName) {
        String lower = fileName != null ? fileName.toLowerCase() : "";

        try {
            if (lower.endsWith(".txt") || lower.endsWith(".csv") || lower.endsWith(".log")) {
                return extractPlainText(fileBytes);
            } else if (lower.endsWith(".pdf")) {
                return extractPdfText(fileBytes);
            } else if (lower.endsWith(".docx")) {
                return extractDocxText(fileBytes);
            } else if (lower.endsWith(".xlsx")) {
                return extractXlsxText(fileBytes);
            } else if (lower.endsWith(".pptx")) {
                return extractPptxText(fileBytes);
            } else {
                return "不支持的文件格式：" + getExtension(fileName);
            }
        } catch (Exception e) {
            log.error("提取文件文本失败: fileName={}", fileName, e);
            return "文件解析失败：" + e.getMessage();
        }
    }

    /**
     * 提取纯文本文件内容，自动检测编码。
     *
     * <p>编码检测策略（按优先级依次尝试）：</p>
     * <ol>
     *   <li>UTF-8 — 覆盖绝大多数现代文件，不会抛异常但中文可能乱码</li>
     *   <li>GBK — 中文 Windows 环境下的常见编码，覆盖遗留系统文件</li>
     *   <li>平台默认编码 — 兜底方案，使用 JVM 默认 charset（中文 Windows 通常为 GBK）</li>
     * </ol>
     *
     * <p>注意：此方法不会抛出异常，总能返回一个字符串（最差情况为乱码）。</p>
     *
     * @param bytes 文本文件字节数组（.txt / .csv / .log）
     * @return 解码后的文本字符串
     */
    private String extractPlainText(byte[] bytes) {
        try {
            return new String(bytes, "UTF-8");
        } catch (Exception e) {
            try {
                return new String(bytes, "GBK");
            } catch (Exception ex) {
                return new String(bytes);
            }
        }
    }

    /**
     * 使用 Apache PDFBox 从 PDF 文件中提取文本。
     *
     * <p>使用 {@link RandomAccessReadBuffer} 从内存字节数组加载 PDF，
     * 避免写入临时文件。设置 {@code setSortByPosition(true)} 以
     * 按页面内位置的上下顺序输出文本，而非 PDF 内部元素顺序。</p>
     *
     * <p>限制：无法提取扫描版 PDF（图片型）中的文字，需要 OCR 前置处理。</p>
     *
     * @param bytes PDF 文件字节数组
     * @return 提取的纯文本内容（已 trim），空 PDF 返回空字符串
     * @throws Exception PDF 解析异常（文件损坏、加密、格式错误等）
     */
    private String extractPdfText(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(bytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            return text != null ? text.trim() : "";
        }
    }

    /**
     * 使用 Apache POI 从 Word 文档（.docx）中提取段落文本。
     *
     * <p>遍历 {@link XWPFDocument} 的所有段落，跳过空行，按段落顺序拼接。
     * 不提取表格、页眉页脚、批注等非段落域内容。</p>
     *
     * <p>注意：仅支持 {@code .docx}（OOXML 格式），不支持旧版 {@code .doc}（OLE2 格式）。</p>
     *
     * @param bytes DOCX 文件字节数组
     * @return 提取的段落文本，段落间以换行分隔
     * @throws Exception 文档解析异常（文件损坏或非 DOCX 格式）
     */
    private String extractDocxText(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> {
                String line = p.getText();
                if (line != null && !line.isBlank()) {
                    sb.append(line).append("\n");
                }
            });
            return sb.toString().trim();
        }
    }

    /**
     * 使用 Apache POI 从 Excel 工作簿（.xlsx）中提取所有单元格内容。
     *
     * <p>遍历所有工作表和行，将单元格值以制表符（tab）分隔拼接。
     * 每个工作表以「【工作表名】」作为标题输出，形成结构化的文本表示。</p>
     *
     * <p>注意：仅支持 {@code .xlsx}（OOXML 格式），不支持旧版 {@code .xls}。
     * 公式单元格会尝试获取计算后的值（字符串优先，数值兜底）。</p>
     *
     * @param bytes XLSX 文件字节数组
     * @return 结构化文本（工作表名 + 行列数据），各表以空行分隔
     * @throws Exception 工作簿解析异常
     */
    private String extractXlsxText(byte[] bytes) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            wb.forEach(sheet -> {
                sb.append("【").append(sheet.getSheetName()).append("】\n");
                sheet.forEach(row -> {
                    StringBuilder rowText = new StringBuilder();
                    row.forEach(cell -> {
                        String val = getCellString(cell);
                        if (!val.isEmpty()) {
                            rowText.append(val).append("\t");
                        }
                    });
                    if (rowText.length() > 0) {
                        sb.append(rowText.toString().trim()).append("\n");
                    }
                });
                sb.append("\n");
            });
            return sb.toString().trim();
        }
    }

    /**
     * 将 Excel 单元格值安全地转为字符串。
     *
     * <p>使用 Java 14+ switch 表达式按单元格类型分发处理：</p>
     * <ul>
     *   <li>{@code STRING} — 直接返回字符串值</li>
     *   <li>{@code NUMERIC} — 整数去除小数点（如 42.0 → "42"），小数保留原样</li>
     *   <li>{@code BOOLEAN} — 转为 "true"/"false"</li>
     *   <li>{@code FORMULA} — 优先获取公式计算后的字符串结果，异常时回退到数值</li>
     *   <li>其他（BLANK、ERROR 等）— 返回空字符串</li>
     * </ul>
     *
     * @param cell Apache POI 单元格对象，可能为不同 CellType
     * @return 单元格文本值，空单元格返回空字符串
     */
    private String getCellString(org.apache.poi.ss.usermodel.Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                yield val == Math.floor(val) && !Double.isInfinite(val)
                        ? String.valueOf((long) val) : String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue(); }
                catch (Exception e) { yield String.valueOf(cell.getNumericCellValue()); }
            }
            default -> "";
        };
    }

    /**
     * 使用 Apache POI 从 PowerPoint 演示文稿（.pptx）中提取文本。
     *
     * <p>遍历每一张幻灯片，从所有 {@link org.apache.poi.xslf.usermodel.XSLFTextShape}
     * 文本形状中提取文字。幻灯片间以空行分隔，形状间以换行分隔。</p>
     *
     * <p>注意：仅支持 {@code .pptx}（OOXML 格式），不支持旧版 {@code .ppt}。
     * 图片、图表中的文本无法提取。</p>
     *
     * @param bytes PPTX 文件字节数组
     * @return 提取的文本内容，幻灯片间以两个换行符分隔
     * @throws Exception 演示文稿解析异常
     */
    private String extractPptxText(byte[] bytes) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            ppt.getSlides().forEach(slide -> {
                slide.getShapes().forEach(shape -> {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            sb.append(text).append("\n");
                        }
                    }
                });
                sb.append("\n");
            });
            return sb.toString().trim();
        }
    }

/**
     * 调用 LLM 生成文件内容摘要。
     *
     * @param content  文件提取的文本内容
     * @param fileName 文件名（用于提示词）
     * @return LLM 生成的摘要文本
     */
    /**
     * 调用 DeepSeek Chat API 生成文件内容摘要。
     *
     * <p>发送 system prompt 设定"专业文档分析助手"角色，要求：
     * 200 字以内、中文回复。使用较低的 temperature（0.3）以获得
     * 更确定性和一致性的摘要结果。</p>
     *
     * <p>与 {@link LlmService} 的对话功能独立，原因是：</p>
     * <ul>
     *   <li>文档摘要不需要 Function Calling 能力</li>
     *   <li>不需要维护用户对话历史</li>
     *   <li>使用更低的 temperature 和更短的 max_tokens</li>
     * </ul>
     *
     * @param content  文件提取的纯文本（可能为截断后的内容）
     * @param fileName 原始文件名，嵌入提示词以提供上下文
     * @return LLM 生成的中文摘要文本，或错误提示
     */
    private String callLlm(String content, String fileName) {
        try {
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "system", "content",
                            "你是一个专业的文档分析助手。请根据用户提供的文件内容进行简洁总结，控制在200字以内，用中文回复。"),
                    Map.of("role", "user", "content",
                            "请总结以下文件「" + fileName + "」的内容：\n\n" + content)
            );

            Map<String, Object> requestBody = new HashMap<>(Map.of(
                    "model", model,
                    "messages", messages,
                    "temperature", 0.3,
                    "max_tokens", 512
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            String requestJson = objectMapper.writeValueAsString(requestBody);
            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    baseUrl + "/v1/chat/completions", entity, String.class);

            String responseBody = response.getBody();
            if (responseBody == null) {
                return "抱歉，AI 服务返回为空，请稍后再试。";
            }

            JsonNode root = objectMapper.readTree(responseBody);
            if (root.has("error")) {
                log.error("DeepSeek API 错误: {}", root.get("error"));
                return "抱歉，文档分析服务暂时不可用。";
            }

            String reply = root.get("choices").get(0).get("message").get("content").asText();
            return reply != null ? reply.trim() : "未能生成总结。";

        } catch (Exception e) {
            log.error("文件总结 LLM 调用失败", e);
            return "抱歉，文档分析失败，请稍后再试。";
        }
    }

    /**
     * 从文件名中提取扩展名（含点号，小写）。
     *
     * <p>提取规则：取最后一个 {@code .} 之后的部分转为小写。
     * 无扩展名时返回 "未知"。</p>
     *
     * @param fileName 文件名，可能为 {@code null}
     * @return 小写扩展名（如 {@code ".pdf"}、{@code ".docx"}），无法识别返回 {@code "未知"}
     */
    private String getExtension(String fileName) {
        if (fileName == null) return "未知";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot).toLowerCase() : "未知";
    }
}
