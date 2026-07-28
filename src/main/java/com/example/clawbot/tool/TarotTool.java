package com.example.clawbot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// 塔罗牌占卜工具：LLM 可调用进行多阶段交互式塔罗牌占卜
@Slf4j
@Component
@RequiredArgsConstructor
public class TarotTool {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    private static final List<String> MAJOR_ARCANA = List.of(
            "愚者", "魔术师", "女祭司", "女皇", "皇帝", "教皇", "恋人", "战车",
            "力量", "隐士", "命运之轮", "正义", "倒吊人", "死神", "节制", "恶魔",
            "高塔", "星星", "月亮", "太阳", "审判", "世界"
    );

    private static final List<String> WANDS = List.of(
            "权杖一", "权杖二", "权杖三", "权杖四", "权杖五", "权杖六", "权杖七",
            "权杖八", "权杖九", "权杖十", "权杖侍从", "权杖骑士", "权杖王后", "权杖国王"
    );

    private static final List<String> CUPS = List.of(
            "圣杯一", "圣杯二", "圣杯三", "圣杯四", "圣杯五", "圣杯六", "圣杯七",
            "圣杯八", "圣杯九", "圣杯十", "圣杯侍从", "圣杯骑士", "圣杯王后", "圣杯国王"
    );

    private static final List<String> SWORDS = List.of(
            "宝剑一", "宝剑二", "宝剑三", "宝剑四", "宝剑五", "宝剑六", "宝剑七",
            "宝剑八", "宝剑九", "宝剑十", "宝剑侍从", "宝剑骑士", "宝剑王后", "宝剑国王"
    );

    private static final List<String> PENTACLES = List.of(
            "星币一", "星币二", "星币三", "星币四", "星币五", "星币六", "星币七",
            "星币八", "星币九", "星币十", "星币侍从", "星币骑士", "星币王后", "星币国王"
    );

    private static final List<String> FULL_DECK;

    static {
        List<String> deck = new ArrayList<>();
        deck.addAll(MAJOR_ARCANA);
        deck.addAll(WANDS);
        deck.addAll(CUPS);
        deck.addAll(SWORDS);
        deck.addAll(PENTACLES);
        FULL_DECK = Collections.unmodifiableList(deck);
    }

    private static final Map<String, String[]> SPREAD_INFO = new LinkedHashMap<>();
    static {
        SPREAD_INFO.put("single", new String[]{"单牌指引阵", "1", "单张牌提供直接指引，适合简单问题或每日指引"});
        SPREAD_INFO.put("three_timeline", new String[]{"三牌时序阵", "3", "过去/现在/未来，适合分析事情发展趋势"});
        SPREAD_INFO.put("love_triangle", new String[]{"爱情三角阵", "3", "你/对方/关系，适合分析感情状况"});
        SPREAD_INFO.put("decision", new String[]{"二选一牌阵", "5", "分析两种选择的利弊与结果"});
        SPREAD_INFO.put("celtic_cross", new String[]{"凯尔特十字阵", "10", "深度分析复杂问题，提供全面视角"});
    }

    private static final Map<String, List<String>> SPREAD_POSITIONS = new HashMap<>();
    static {
        SPREAD_POSITIONS.put("single", List.of("指引"));
        SPREAD_POSITIONS.put("three_timeline", List.of("过去", "现在", "未来"));
        SPREAD_POSITIONS.put("love_triangle", List.of("你的状态", "对方的状态", "关系走向"));
        SPREAD_POSITIONS.put("decision", List.of("现状", "选择A的利", "选择A的弊", "选择B的利", "选择B的弊"));
        SPREAD_POSITIONS.put("celtic_cross", List.of(
                "现状核心", "阻碍或助力", "意识层面", "潜意识层面", "过去影响",
                "近期未来", "自身态度", "环境因素", "希望与恐惧", "最终结果"
        ));
    }

    private enum SessionState {
        WAITING_QUESTION,
        WAITING_SPREAD,
        WAITING_CARDS,
        COMPLETED
    }

    private static class SessionContext {
        String userQuestion;
        String spreadType;
        Map<Integer, String> numberToCardMap;
        SessionState state;

        SessionContext() {
            this.state = SessionState.WAITING_QUESTION;
        }
    }

    private final Map<String, SessionContext> sessionContexts = new ConcurrentHashMap<>();

    @Tool(name = "tarot_reading", description = "韦特塔罗牌专业解读。当用户想要占卜、算命、预测未来、寻求指引时使用此工具。采用多阶段交互：先输入问题，再选择牌阵，最后选择牌码进行抽牌。")
    public String tarotReading(
            @ToolParam(description = "操作类型：start（开始新的占卜流程）、select_spread（选择牌阵）、draw（用户选择数字进行抽牌）、continue（询问是否继续占卜）") String action,
            @ToolParam(description = "用户唯一标识，用于会话状态管理") String user_id,
            @ToolParam(required = false, description = "用户想要占卜的具体问题") String user_question,
            @ToolParam(required = false, description = "选择的牌阵类型：single(单牌), three_timeline(三牌时序), love_triangle(爱情三角), decision(二选一), celtic_cross(凯尔特十字)") String spread_type,
            @ToolParam(required = false, description = "用户选择的牌码，用空格分隔，如 7 22 19") String selected_codes,
            @ToolParam(required = false, description = "是否继续占卜：yes 继续，no 结束") String continue_choice) {

        if (action == null || action.trim().isEmpty()) {
            return "{\"status\":\"error\",\"message\":\"action 参数不能为空\"}";
        }
        if (user_id == null || user_id.trim().isEmpty()) {
            return "{\"status\":\"error\",\"message\":\"user_id 参数不能为空\"}";
        }

        String effectiveAction = action.trim();
        String effectiveUserId = user_id.trim();

        SessionContext context = sessionContexts.computeIfAbsent(effectiveUserId, k -> new SessionContext());

        log.info("[行动] LLM调用工具: tarot(action=\"{}\", userId=\"{}\") → 塔罗牌占卜多阶段交互 (当前状态: {})", effectiveAction, effectiveUserId, context.state);

        try {
            switch (effectiveAction) {
                case "start":
                    return handleStart(context, user_question);
                case "select_spread":
                    return handleSelectSpread(context, spread_type);
                case "draw":
                    return handleDraw(context, selected_codes);
                case "continue":
                    return handleContinue(context, continue_choice, effectiveUserId);
                default:
                    return objectMapper.writeValueAsString(Map.of(
                            "status", "error",
                            "message", "不支持的操作类型: " + effectiveAction
                    ));
            }
        } catch (Exception e) {
            log.error("[异常] 工具调用失败 tarot(action=\"{}\") | 原因: {} | 建议: 检查塔罗工具逻辑",
                    effectiveAction, effectiveUserId, context.state, e.getMessage(), e);
            return "工具调用失败：" + e.getMessage();
        }
    }

    public String getSpreadSelectionMessage() {
        StringBuilder sb = new StringBuilder("🔮 请选择牌阵：\n\n");
        int i = 1;
        for (Map.Entry<String, String[]> entry : SPREAD_INFO.entrySet()) {
            String[] info = entry.getValue();
            sb.append(i++).append(". ").append(info[0])
                    .append("（").append(info[1]).append("张牌）\n");
            sb.append("   ").append(info[2]).append("\n\n");
        }
        sb.append("请回复牌阵编号或名称，例如：1 或 three_timeline");
        return sb.toString();
    }

    public String getContinueMessage() {
        return "🌟 本次占卜结束。是否还要再进行一次占卜？\n\n请回复「是」或「否」";
    }

    private String handleStart(SessionContext context, String userQuestion) throws Exception {
        String question = userQuestion != null ? userQuestion.trim() : "";

        if (question.isEmpty()) {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "waiting_for_question",
                    "message", "🔮 欢迎来到塔罗占卜！\n\n请告诉我您想要占卜的问题：\n例如：这段感情会顺利吗？、我应该换工作吗？"
            ));
        }

        context.userQuestion = question;
        context.state = SessionState.WAITING_SPREAD;

        return objectMapper.writeValueAsString(Map.of(
                "status", "waiting_for_spread",
                "user_question", question,
                "message", "好的，问题已收到：「" + question + "」\n\n" + getSpreadSelectionMessage()
        ));
    }

    private String handleSelectSpread(SessionContext context, String spreadType) throws Exception {
        if (context.state != SessionState.WAITING_SPREAD) {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "当前状态不允许选择牌阵，请先输入问题"
            ));
        }

        String effectiveSpreadType = spreadType != null ? spreadType.trim() : "";

        if (effectiveSpreadType.matches("\\d+")) {
            int index = Integer.parseInt(effectiveSpreadType) - 1;
            if (index >= 0 && index < SPREAD_INFO.size()) {
                effectiveSpreadType = new ArrayList<>(SPREAD_INFO.keySet()).get(index);
            } else {
                return objectMapper.writeValueAsString(Map.of(
                        "status", "error",
                        "message", "无效的牌阵编号，请选择 1-" + SPREAD_INFO.size()
                ));
            }
        }

        if (!SPREAD_INFO.containsKey(effectiveSpreadType)) {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "无效的牌阵类型，请从以下选项中选择：" + String.join(", ", SPREAD_INFO.keySet())
            ));
        }

        context.spreadType = effectiveSpreadType;
        context.numberToCardMap = generateNumberToCardMap();
        context.state = SessionState.WAITING_CARDS;

        String spreadName = SPREAD_INFO.get(effectiveSpreadType)[0];
        int cardCount = Integer.parseInt(SPREAD_INFO.get(effectiveSpreadType)[1]);

        String message = "🎴 已选择「" + spreadName + "」，需要选择 " + cardCount + " 张牌\n\n"
                + "🌙 闭眼深呼吸，冥想你的问题\n"
                + "💫 睁开眼后，请从 1-78 中选择 " + cardCount + " 个数字，用空格分隔\n"
                + "例如：7 22 19\n\n"
                + "（提示：选牌后系统会揭晓每张数字对应的塔罗牌）\n"
                + "🔮 本次占卜的数字-牌对应关系已随机生成";

        return objectMapper.writeValueAsString(Map.of(
                "status", "waiting_for_cards",
                "spread_type", effectiveSpreadType,
                "spread_name", spreadName,
                "card_count", cardCount,
                "user_question", context.userQuestion,
                "message", message
        ));
    }

    private String handleDraw(SessionContext context, String selectedCodes) throws Exception {
        if (context.state != SessionState.WAITING_CARDS) {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "当前状态不允许抽牌，请先选择牌阵"
            ));
        }

        String codes = selectedCodes != null ? selectedCodes.trim() : "";
        List<String> positions = SPREAD_POSITIONS.get(context.spreadType);
        int requiredCount = positions.size();

        if (context.numberToCardMap == null) {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "牌映射已过期，请重新选择牌阵"
            ));
        }

        List<Integer> numbers = new ArrayList<>();
        String[] parts = codes.split("\\s+");
        for (String part : parts) {
            try {
                int num = Integer.parseInt(part.trim());
                if (num >= 1 && num <= 78 && !numbers.contains(num)) {
                    numbers.add(num);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (numbers.size() < requiredCount) {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "需要选择 " + requiredCount + " 个不同的数字（1-78），当前选择了 " + numbers.size() + " 个有效数字"
            ));
        }

        List<Map<String, Object>> cards = new ArrayList<>();
        for (int i = 0; i < requiredCount; i++) {
            int number = numbers.get(i);
            String cardName = context.numberToCardMap.get(number);
            boolean isReversed = random.nextDouble() < 0.5;

            cards.add(Map.of(
                    "position", positions.get(i),
                    "card_number", number,
                    "card", cardName,
                    "orientation", isReversed ? "逆位" : "正位"
            ));
        }

        context.state = SessionState.COMPLETED;

        String interpretationPrompt = buildInterpretationPrompt(context.userQuestion, context.spreadType, cards);

        return buildSuccessResponse(context.spreadType, context.userQuestion, cards, interpretationPrompt);
    }

    private String handleContinue(SessionContext context, String continueChoice, String userId) throws Exception {
        String choice = continueChoice != null ? continueChoice.trim().toLowerCase() : "";

        if ("yes".equals(choice) || "是".equals(choice)) {
            context.userQuestion = null;
            context.spreadType = null;
            context.numberToCardMap = null;
            context.state = SessionState.WAITING_QUESTION;

            return objectMapper.writeValueAsString(Map.of(
                    "status", "waiting_for_question",
                    "message", "🔮 好的，新的占卜开始！\n\n请告诉我您想要占卜的问题："
            ));
        } else if ("no".equals(choice) || "否".equals(choice)) {
            sessionContexts.remove(userId);

            return objectMapper.writeValueAsString(Map.of(
                    "status", "finished",
                    "message", "感谢使用塔罗占卜！祝您一切顺利 ✨"
            ));
        } else {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "请回复「是」或「否」"
            ));
        }
    }

    private Map<Integer, String> generateNumberToCardMap() {
        List<String> shuffledDeck = new ArrayList<>(FULL_DECK);
        Collections.shuffle(shuffledDeck, random);

        Map<Integer, String> map = new LinkedHashMap<>();
        for (int i = 0; i < 78; i++) {
            map.put(i + 1, shuffledDeck.get(i));
        }
        return map;
    }

    private String buildInterpretationPrompt(String userQuestion, String spreadType,
                                               List<Map<String, Object>> cards) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("【塔罗牌解读任务】\n\n");
        prompt.append("占卜问题：").append(userQuestion).append("\n");
        prompt.append("使用牌阵：").append(SPREAD_INFO.get(spreadType)[0]).append("\n\n");

        prompt.append("抽到的牌：\n");
        for (int i = 0; i < cards.size(); i++) {
            Map<String, Object> card = cards.get(i);
            prompt.append(String.format("%d. %s：【%d号 %s】【%s】\n",
                    i + 1, card.get("position"), card.get("card_number"),
                    card.get("card"), card.get("orientation")));
        }

        prompt.append("\n【解读要求】\n");
        prompt.append("1. 分开解读每张牌，紧密结合它对应的位置含义；\n");
        prompt.append("2. 梳理各张牌前后的因果关联；\n");
        prompt.append("3. 紧扣用户提出的问题分析；\n");
        prompt.append("4. 客观说明未来是趋势推演，并非注定；\n");
        prompt.append("5. 语言温和，具备参考性。\n\n");
        prompt.append("请在末尾添加声明：塔罗仅作为心理参考工具，不能作为重大决策唯一依据。");

        return prompt.toString();
    }

    private String buildSuccessResponse(String spreadType, String userQuestion,
                                          List<Map<String, Object>> cards, String interpretationPrompt) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "status", "success",
                "spread_type", spreadType,
                "spread_name", SPREAD_INFO.get(spreadType)[0],
                "user_question", userQuestion,
                "cards", cards,
                "interpretation_prompt", interpretationPrompt,
                "disclaimer", "塔罗仅作为心理参考工具，不能作为重大决策唯一依据。",
                "continue_message", getContinueMessage()
        ));
    }
}
