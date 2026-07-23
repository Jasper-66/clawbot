package com.example.clawbot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 塔罗牌占卜工具。
 *
 * <p>提供专业塔罗牌解读服务，支持多种牌阵。采用多阶段交互流程：</p>
 *
 * <h3>交互流程</h3>
 * <pre>
 * 阶段1：等待问题 → 用户输入问题 → 进入阶段2
 * 阶段2：选择牌阵 → 用户选择牌阵 → 进入阶段3（自主抽牌）或阶段4（随机抽牌）
 * 阶段3：抽牌选择 → 用户选择牌码 → 进入阶段4
 * 阶段4：显示结果 → 用户选择是否继续 → 返回阶段1
 * </pre>
 *
 * <h3>支持的牌阵</h3>
 * <ul>
 *   <li>单牌指引阵（1张）：适合简单是非问题</li>
 *   <li>三牌时序阵（3张）：分析过去、现在、未来</li>
 *   <li>爱情三角阵（3张）：分析感情状况</li>
 *   <li>二选一牌阵（5张）：分析两种选择的利弊</li>
 *   <li>凯尔特十字阵（10张）：深度分析复杂问题</li>
 * </ul>
 *
 * <p>工具负责抽牌并生成解读提示，由 LLM 完成专业解读。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TarotTool {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    private static final String NAME = "tarot_reading";

    // ==================== 78 张塔罗牌定义 ====================

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

    // ==================== 牌阵定义 ====================

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

    // ==================== 会话状态管理 ====================

    /**
     * 会话状态枚举
     */
    private enum SessionState {
        WAITING_QUESTION,    // 等待用户输入问题
        WAITING_SPREAD,      // 等待用户选择牌阵
        WAITING_CARDS,       // 等待用户选择牌码
        COMPLETED            // 占卜完成
    }

    /**
     * 会话上下文
     */
    private static class SessionContext {
        String userQuestion;
        String spreadType;
        /**
         * 数字到塔罗牌的随机映射表。
         * <p>每次选择牌阵时生成：key 为用户输入的数字（1-78），value 为对应的塔罗牌名。
         * 这样每次占卜的数字-牌对应关系都不同，增强神秘感和随机性。</p>
         */
        Map<Integer, String> numberToCardMap;
        SessionState state;

        SessionContext() {
            this.state = SessionState.WAITING_QUESTION;
        }
    }

    /** 会话状态缓存：用户ID → 上下文 */
    private final Map<String, SessionContext> sessionContexts = new ConcurrentHashMap<>();

    /**
     * 获取工具名称。
     *
     * @return 工具标识名
     */
    public String getToolName() {
        return NAME;
    }

    /**
     * 获取工具定义。
     *
     * @return OpenAI Function Calling 格式的工具定义
     */
    public Map<String, Object> getToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", NAME,
                        "description", "韦特塔罗牌专业解读。当用户想要占卜、算命、预测未来、寻求指引时使用此工具。" +
                                "采用多阶段交互：先输入问题，再选择牌阵，最后选择牌码（支持自主抽牌或随机抽牌）。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "action", Map.of(
                                                "type", "string",
                                                "description", "操作类型：" +
                                                        "- 'start'：开始新的占卜流程 " +
                                                        "- 'select_spread'：选择牌阵 " +
                                                        "- 'draw'：用户选择牌码进行抽牌 " +
                                                        "- 'random'：随机抽牌 " +
                                                        "- 'continue'：询问是否继续占卜",
                                                "enum", List.of("start", "select_spread", "draw", "random", "continue")
                                        ),
                                        "user_id", Map.of(
                                                "type", "string",
                                                "description", "用户唯一标识，用于会话状态管理"
                                        ),
                                        "user_question", Map.of(
                                                "type", "string",
                                                "description", "用户想要占卜的具体问题"
                                        ),
                                        "spread_type", Map.of(
                                                "type", "string",
                                                "description", "选择的牌阵类型：single(单牌), three_timeline(三牌时序), love_triangle(爱情三角), decision(二选一), celtic_cross(凯尔特十字)"
                                        ),
                                        "selected_codes", Map.of(
                                                "type", "string",
                                                "description", "用户选择的牌码，用空格分隔，如 '7 22 19'"
                                        ),
                                        "continue_choice", Map.of(
                                                "type", "string",
                                                "description", "是否继续占卜：'yes' 继续，'no' 结束"
                                        )
                                ),
                                "required", List.of("action", "user_id")
                        )
                )
        );
    }

    /**
     * 获取牌阵选择列表文本。
     *
     * @return 牌阵列表说明
     */
    public String getSpreadSelectionMessage() {
        StringBuilder sb = new StringBuilder("🔮 请选择牌阵：\n\n");
        int i = 1;
        for (Map.Entry<String, String[]> entry : SPREAD_INFO.entrySet()) {
            String id = entry.getKey();
            String[] info = entry.getValue();
            sb.append(i++).append(". ").append(info[0])
                    .append("（").append(info[1]).append("张牌）\n");
            sb.append("   ").append(info[2]).append("\n\n");
        }
        sb.append("请回复牌阵编号或名称，例如：1 或 three_timeline");
        return sb.toString();
    }

    /**
     * 获取继续询问消息。
     *
     * @return 继续询问文本
     */
    public String getContinueMessage() {
        return "🌟 本次占卜结束。是否还要再进行一次占卜？\n\n请回复「是」或「否」";
    }

    /**
     * 校验并执行模型返回的工具调用。
     *
     * @param functionName  工具名称
     * @param argumentsJson 工具参数 JSON
     * @return 执行结果 JSON
     */
    public String execute(String functionName, String argumentsJson) {
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }

        try {
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String action = arguments.path("action").asText("").trim();
            String userId = arguments.path("user_id").asText("").trim();

            if (userId.isEmpty()) {
                return "工具调用失败：user_id 参数不能为空";
            }

            // 获取或创建会话上下文
            SessionContext context = sessionContexts.computeIfAbsent(userId, k -> new SessionContext());

            log.info("塔罗占卜执行: action={}, userId={}, state={}", action, userId, context.state);

            switch (action) {
                case "start":
                    return handleStart(context, arguments);
                case "select_spread":
                    return handleSelectSpread(context, arguments);
                case "draw":
                    return handleDraw(context, arguments);
                case "random":
                    return handleRandom(context, arguments);
                case "continue":
                    return handleContinue(context, arguments);
                default:
                    return objectMapper.writeValueAsString(Map.of(
                            "status", "error",
                            "message", "不支持的操作类型: " + action
                    ));
            }

        } catch (Exception e) {
            log.error("塔罗占卜工具执行失败: {}", e.getMessage());
            return "工具调用失败：" + e.getMessage();
        }
    }

    /**
     * 处理开始操作。
     */
    private String handleStart(SessionContext context, JsonNode arguments) throws Exception {
        String userQuestion = arguments.path("user_question").asText("").trim();

        if (userQuestion.isEmpty()) {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "waiting_for_question",
                    "message", "🔮 欢迎来到塔罗占卜！\n\n请告诉我您想要占卜的问题：\n例如：这段感情会顺利吗？、我应该换工作吗？"
            ));
        }

        context.userQuestion = userQuestion;
        context.state = SessionState.WAITING_SPREAD;

        return objectMapper.writeValueAsString(Map.of(
                "status", "waiting_for_spread",
                "user_question", userQuestion,
                "message", "好的，问题已收到：「" + userQuestion + "」\n\n" + getSpreadSelectionMessage()
        ));
    }

    /**
     * 处理选择牌阵操作。
     */
    private String handleSelectSpread(SessionContext context, JsonNode arguments) throws Exception {
        if (context.state != SessionState.WAITING_SPREAD) {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "当前状态不允许选择牌阵，请先输入问题"
            ));
        }

        String spreadType = arguments.path("spread_type").asText("").trim();

        // 如果用户输入的是编号，转换为牌阵ID
        if (spreadType.matches("\\d+")) {
            int index = Integer.parseInt(spreadType) - 1;
            if (index >= 0 && index < SPREAD_INFO.size()) {
                spreadType = new ArrayList<>(SPREAD_INFO.keySet()).get(index);
            } else {
                return objectMapper.writeValueAsString(Map.of(
                        "status", "error",
                        "message", "无效的牌阵编号，请选择 1-" + SPREAD_INFO.size()
                ));
            }
        }

        if (!SPREAD_INFO.containsKey(spreadType)) {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "无效的牌阵类型，请从以下选项中选择：" + String.join(", ", SPREAD_INFO.keySet())
            ));
        }

        context.spreadType = spreadType;
        // 生成随机数字-牌映射（每次占卜都不同）
        context.numberToCardMap = generateNumberToCardMap();
        context.state = SessionState.WAITING_CARDS;

        String spreadName = SPREAD_INFO.get(spreadType)[0];
        int cardCount = Integer.parseInt(SPREAD_INFO.get(spreadType)[1]);

        // 只提示范围，不输出完整牌码列表
        String message = "🎴 已选择「" + spreadName + "」，需要选择 " + cardCount + " 张牌\n\n"
                + "🌙 闭眼深呼吸，冥想你的问题\n"
                + "💫 睁开眼后，请从 1-78 中选择 " + cardCount + " 个数字，用空格分隔\n"
                + "例如：7 22 19\n\n"
                + "（提示：选牌后系统会揭晓每张数字对应的塔罗牌）\n"
                + "🔮 本次占卜的数字-牌对应关系已随机生成";

        return objectMapper.writeValueAsString(Map.of(
                "status", "waiting_for_cards",
                "spread_type", spreadType,
                "spread_name", spreadName,
                "card_count", cardCount,
                "user_question", context.userQuestion,
                "message", message
        ));
    }

/**
     * 处理用户选择数字抽牌。
     *
     * <p>用户输入 1-78 范围内的数字，系统根据本次会话的随机映射表找到对应的塔罗牌。</p>
     */
    private String handleDraw(SessionContext context, JsonNode arguments) throws Exception {
        if (context.state != SessionState.WAITING_CARDS) {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "当前状态不允许抽牌，请先选择牌阵"
            ));
        }

        String selectedCodes = arguments.path("selected_codes").asText("").trim();
        List<String> positions = SPREAD_POSITIONS.get(context.spreadType);
        int requiredCount = positions.size();

        if (context.numberToCardMap == null) {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "牌映射已过期，请重新选择牌阵"
            ));
        }

        // 解析用户输入的数字（1-78 范围）
        List<Integer> numbers = new ArrayList<>();
        String[] parts = selectedCodes.split("\\s+");
        for (String part : parts) {
            try {
                int num = Integer.parseInt(part.trim());
                if (num >= 1 && num <= 78 && !numbers.contains(num)) {
                    numbers.add(num);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // 检查数量是否正确
        if (numbers.size() < requiredCount) {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "需要选择 " + requiredCount + " 个不同的数字（1-78），当前选择了 " + numbers.size() + " 个有效数字"
            ));
        }

        // 构建抽牌结果（使用本次会话的随机映射表）
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

/**
     * 处理随机抽牌。
     *
     * <p>如果用户还没选择牌阵，强制返回让用户选择牌阵，不设置默认值。</p>
     */
    private String handleRandom(SessionContext context, JsonNode arguments) throws Exception {
        // 如果还没输入问题，先收集问题
        if (context.state == SessionState.WAITING_QUESTION) {
            String userQuestion = arguments.path("user_question").asText("").trim();
            if (userQuestion.isEmpty()) {
                return objectMapper.writeValueAsString(Map.of(
                        "status", "waiting_for_question",
                        "message", "🔮 欢迎来到塔罗占卜！\n\n请告诉我您想要占卜的问题："
                ));
            }
            context.userQuestion = userQuestion;
            context.state = SessionState.WAITING_SPREAD;
        }

        // 如果还没选择牌阵，强制让用户选择，不设置默认值
        if (context.state == SessionState.WAITING_SPREAD || context.spreadType == null) {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "waiting_for_spread",
                    "user_question", context.userQuestion,
                    "message", "问题已收到：「" + context.userQuestion + "」\n\n" + getSpreadSelectionMessage()
            ));
        }

        List<Map<String, Object>> cards = drawCards(context.spreadType);
        String interpretationPrompt = buildInterpretationPrompt(context.userQuestion, context.spreadType, cards);

        context.state = SessionState.COMPLETED;

        return buildSuccessResponse(context.spreadType, context.userQuestion, cards, interpretationPrompt);
    }

    /**
     * 处理继续询问。
     */
    private String handleContinue(SessionContext context, JsonNode arguments) throws Exception {
        String choice = arguments.path("continue_choice").asText("").trim().toLowerCase();

        if ("yes".equals(choice) || "是".equals(choice)) {
            // 重置会话状态，回到问问题阶段
            context.userQuestion = null;
            context.spreadType = null;
            context.numberToCardMap = null;
            context.state = SessionState.WAITING_QUESTION;

            return objectMapper.writeValueAsString(Map.of(
                    "status", "waiting_for_question",
                    "message", "🔮 好的，新的占卜开始！\n\n请告诉我您想要占卜的问题："
            ));
        } else if ("no".equals(choice) || "否".equals(choice)) {
            // 清除会话上下文
            sessionContexts.remove(userId());

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

    /**
     * 生成随机数字-牌映射表。
     *
     * <p>将 78 张牌随机打乱后，分配给 1-78 的数字。
     * 每次调用都生成不同的映射，增强占卜的随机性和神秘感。</p>
     *
     * @return 数字到牌名的映射
     */
    private Map<Integer, String> generateNumberToCardMap() {
        List<String> shuffledDeck = new ArrayList<>(FULL_DECK);
        Collections.shuffle(shuffledDeck, random);

        Map<Integer, String> map = new LinkedHashMap<>();
        for (int i = 0; i < 78; i++) {
            map.put(i + 1, shuffledDeck.get(i));
        }
        return map;
    }

    /**
     * 根据牌阵随机抽牌。
     */
    private List<Map<String, Object>> drawCards(String spreadType) {
        List<String> positions = SPREAD_POSITIONS.get(spreadType);
        int cardCount = positions.size();

        List<String> shuffledDeck = new ArrayList<>(FULL_DECK);
        Collections.shuffle(shuffledDeck, random);

        List<Map<String, Object>> drawnCards = new ArrayList<>();
        for (int i = 0; i < cardCount; i++) {
            String cardName = shuffledDeck.get(i);
            boolean isReversed = random.nextDouble() < 0.5;

            drawnCards.add(Map.of(
                    "position", positions.get(i),
                    "card", cardName,
                    "orientation", isReversed ? "逆位" : "正位"
            ));
        }

        return drawnCards;
    }

    /**
     * 构建解读提示。
     */
    private String buildInterpretationPrompt(String userQuestion, String spreadType,
                                               List<Map<String, Object>> cards) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("【塔罗牌解读任务】\n\n");
        prompt.append("占卜问题：").append(userQuestion).append("\n");
        prompt.append("使用牌阵：").append(SPREAD_INFO.get(spreadType)[0]).append("\n\n");

        prompt.append("抽到的牌：\n");
        for (int i = 0; i < cards.size(); i++) {
            Map<String, Object> card = cards.get(i);
            if (card.containsKey("card_number")) {
                prompt.append(String.format("%d. %s：【%d号 %s】【%s】\n",
                        i + 1, card.get("position"), card.get("card_number"),
                        card.get("card"), card.get("orientation")));
            } else {
                prompt.append(String.format("%d. %s：【%s】【%s】\n",
                        i + 1, card.get("position"), card.get("card"), card.get("orientation")));
            }
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

    /**
     * 构建成功响应。
     */
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

    /**
     * 生成临时用户ID（用于日志）。
     */
    private String userId() {
        return "temp_user_" + Thread.currentThread().getId();
    }
}