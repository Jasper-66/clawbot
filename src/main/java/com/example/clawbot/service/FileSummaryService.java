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

/** 文件总结服务，使用 Apache PDFBox/POI 提取文档文本并通过 LLM 生成摘要。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileSummaryService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${deepseek.api.key}")
    private String apiKey;

    @Value("${deepseek.api.base-url}")
    private String baseUrl;

    @Value("${deepseek.api.model}")
    private String model;

    private static final int MAX_TEXT_LENGTH = 4000;

    /** 总结文件内容：根据扩展名自动选择提取引擎 → 截断超长文本 → LLM 摘要。 */
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

    /** 根据文件扩展名路由到对应的文本提取方法。 */
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

    /** 提取纯文本内容，依次尝试 UTF-8 → GBK → 平台默认编码。 */
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

    /** 使用 Apache PDFBox 从 PDF 提取文本。 */
    private String extractPdfText(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(bytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            return text != null ? text.trim() : "";
        }
    }

    /** 使用 Apache POI 从 Word 文档提取段落文本。 */
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

    /** 使用 Apache POI 从 Excel 工作簿提取所有单元格内容。 */
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

    /** 按单元格类型（STRING/NUMERIC/BOOLEAN/FORMULA）安全转为字符串。 */
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

    /** 使用 Apache POI 从 PowerPoint 提取幻灯片文本。 */
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

    /** 调用 DeepSeek LLM 生成 200 字以内的中文文档摘要。 */
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

    /** 从文件名提取小写扩展名。 */
    private String getExtension(String fileName) {
        if (fileName == null) return "未知";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot).toLowerCase() : "未知";
    }
}
