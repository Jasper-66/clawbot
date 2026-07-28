package com.example.clawbot.service;

import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 日历查询服务 — 黄历、节假日、放假安排数据提供者。
 *
 * <p>提供三类日历查询能力：</p>
 * <ul>
 *   <li><b>黄历查询</b> — 使用 lunar-java 本地库计算，无需外部 API，返回宜忌、冲煞等信息</li>
 *   <li><b>节假日查询</b> — 调用 Timor.tech API，判断指定日期是否为节假日/工作日</li>
 *   <li><b>放假安排</b> — 调用 Timor.tech API，获取某年全部法定节假日安排</li>
 * </ul>
 *
 * <h3>API 说明</h3>
 * <ul>
 *   <li>黄历：本地计算（cn.6tail:lunar），零网络开销</li>
 *   <li>Timor.tech 节假日：{@code https://timor.tech/api/holiday/info/{date}}（免费）</li>
 *   <li>Timor.tech 放假安排：{@code https://timor.tech/api/holiday/year/{year}}（免费）</li>
 * </ul>
 *
 * @see com.example.clawbot.tool.DateTimeTool
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalendarService {

    /** HTTP 客户端，用于调用外部 API */
    private final RestTemplate restTemplate;

    /** Jackson JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 查询指定日期的黄历信息。
     *
     * <p>使用 lunar-java 本地库计算农历、干支、宜忌、冲煞等传统黄历信息，
     * 无需外部 API 调用，零网络开销。</p>
     *
     * <h3>返回示例</h3>
     * <pre>
     * 📅 2024-01-15 黄历信息
     *
     * 🌙 农历：癸卯年 腊月初五
     * 📆 干支：癸卯年 乙丑月 戊寅日（兔）
     * ✅ 宜：嫁娶、开光、出行
     * ❌ 忌：动土、安葬
     * ⚡ 冲：猴 煞：北
     * </pre>
     *
     * @param date 日期字符串，格式 yyyy-MM-dd
     * @return 格式化的黄历信息文本，或错误提示
     */
    public String queryAlmanac(String date) {
        log.info("查询黄历（本地计算）: date={}", date);

        try {
            // 解析日期
            String[] parts = date.split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int day = Integer.parseInt(parts[2]);

            // 公历转农历
            Solar solar = Solar.fromYmd(year, month, day);
            Lunar lunar = solar.getLunar();

            // 获取黄历信息
            String yearGZ = lunar.getYearInGanZhi();      // 干支年：癸卯
            String monthGZ = lunar.getMonthInGanZhi();     // 干支月：乙丑
            String dayGZ = lunar.getDayInGanZhi();         // 干支日：戊寅
            String shengXiao = lunar.getYearShengXiao();   // 生肖：兔
            String lunarMonthChinese = lunar.getMonthInChinese();  // 农历月：腊
            String lunarDayChinese = lunar.getDayInChinese();      // 农历日：初五
            String yi = String.join("、", lunar.getDayYi());  // 宜
            String ji = String.join("、", lunar.getDayJi());  // 忌
            String chong = lunar.getDayChong();            // 冲
            String sha = lunar.getDaySha();                // 煞
            String jieQi = lunar.getJieQi();               // 节气（空串表示无节气）

            StringBuilder sb = new StringBuilder();
            sb.append("📅 ").append(date).append(" 黄历信息\n\n");
            sb.append("🌙 农历：").append(yearGZ).append("年 ").append(lunarMonthChinese).append("月").append(lunarDayChinese).append("\n");
            sb.append("📆 干支：").append(yearGZ).append("年 ").append(monthGZ).append("月 ").append(dayGZ).append("日（").append(shengXiao).append("）\n");
            sb.append("✅ 宜：").append(yi.isEmpty() ? "无" : yi).append("\n");
            sb.append("❌ 忌：").append(ji.isEmpty() ? "无" : ji).append("\n");
            sb.append("⚡ 冲：").append(chong).append(" 煞：").append(sha);

            if (!jieQi.isEmpty()) {
                sb.append("\n🌱 节气：").append(jieQi);
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("黄历查询异常: date={}", date, e);
            return "黄历查询失败：" + e.getMessage();
        }
    }

    /**
     * 查询指定日期的节假日信息。
     *
     * <p>调用 Timor.tech 免费 API，判断指定日期是否为法定节假日、
     * 周末或工作日（含调休信息）。</p>
     *
     * @param date 日期字符串，格式 yyyy-MM-dd
     * @return 格式化的节假日信息文本，或错误提示
     */
    public String queryHoliday(String date) {
        log.info("调用Timor.tech节假日API: date={}", date);

        try {
            String url = "https://timor.tech/api/holiday/info/" + date;
            String response = doGet(url);
            JsonNode root = objectMapper.readTree(response);

            int code = root.path("code").asInt(-1);
            if (code != 0) {
                log.error("Timor.tech 节假日API返回错误: code={}, msg={}", code, root.path("msg").asText(""));
                return "节假日查询失败，请确认日期格式正确（yyyy-MM-dd）。";
            }

            JsonNode holiday = root.path("holiday");
            boolean isHoliday = holiday != null && !holiday.isNull();
            String typeName = root.path("type").path("name").asText("未知");

            StringBuilder sb = new StringBuilder();
            sb.append("📅 ").append(date).append(" 节假日信息\n\n");

            if (isHoliday) {
                String name = holiday.path("name").asText("");
                String wage = holiday.path("wage").asText("");
                sb.append("🏷 类型：法定节假日\n");
                sb.append("📝 节日：").append(name).append("\n");
                if (!wage.isEmpty()) {
                    sb.append("💰 加班工资：").append(wage).append("倍\n");
                }
            } else {
                sb.append("🏷 类型：").append(typeName).append("\n");
                if (typeName.contains("班")) {
                    sb.append("⚠️ 今天需要上班（调休补班）\n");
                } else if (typeName.contains("末")) {
                    sb.append("😴 今天是周末，好好休息\n");
                } else {
                    sb.append("💼 今天是工作日\n");
                }
            }

            return sb.toString().trim();

        } catch (Exception e) {
            log.error("节假日查询异常: date={}", date, e);
            return "节假日查询失败：" + e.getMessage();
        }
    }

    /**
     * 查询指定年份的放假安排。
     *
     * <p>调用 Timor.tech 免费 API，获取某年全部法定节假日的
     * 放假安排，包括放假天数、调休安排等。</p>
     *
     * @param yearStr 年份字符串，如 "2024"
     * @return 格式化的全年放假安排文本，或错误提示
     */
    public String querySchedule(String yearStr) {
        log.info("调用Timor.tech放假安排API: year={}", yearStr);

        try {
            String url = "https://timor.tech/api/holiday/year/" + yearStr;
            String response = doGet(url);
            JsonNode root = objectMapper.readTree(response);

            int code = root.path("code").asInt(-1);
            if (code != 0) {
                log.error("Timor.tech 放假安排API返回错误: code={}", code);
                return "放假安排查询失败，请确认年份正确。";
            }

            JsonNode holidays = root.path("holiday");
            if (holidays == null || !holidays.isObject()) {
                return yearStr + "年暂无放假安排数据。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📅 ").append(yearStr).append("年放假安排\n\n");

            holidays.fields().forEachRemaining(entry -> {
                JsonNode day = entry.getValue();
                String name = day.path("name").asText("");
                String dateStr = day.path("date").asText(entry.getKey());
                boolean holiday = day.path("holiday").asBoolean(false);

                if (holiday && !name.isEmpty()) {
                    sb.append("🎉 ").append(name).append("：").append(dateStr).append("\n");
                }
            });

            if (sb.toString().endsWith("\n\n")) {
                sb.append("暂无放假安排数据。");
            }

            return sb.toString().trim();

        } catch (Exception e) {
            log.error("放假安排查询异常: year={}", yearStr, e);
            return "放假安排查询失败：" + e.getMessage();
        }
    }

    /**
     * 带 User-Agent 的 GET 请求。
     *
     * <p>Timor.tech API 会拒绝没有 User-Agent 的请求（返回 403），
     * 此方法统一设置浏览器 User-Agent 后发起 GET 请求。</p>
     *
     * @param url 请求 URL
     * @return 响应体字符串
     */
    private String doGet(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        return response.getBody();
    }
}
