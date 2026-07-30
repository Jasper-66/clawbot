package com.example.clawbot.service;

import com.example.clawbot.entity.ScheduledTaskEntity;
import com.example.clawbot.repository.ScheduledTaskRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 调度引擎 — 管理定时提醒和周期任务。
 *
 * <p>使用 {@link ScheduledExecutorService} 作为调度核心，支持动态创建/取消任务。
 * 任务持久化到 SQLite，应用启动时自动恢复未完成的任务。</p>
 */
@Slf4j
@Service
public class SchedulerService {

    private final ScheduledTaskRepository taskRepository;
    private final NewsService newsService;
    private final WeatherService weatherService;
    private final LlmService llmService;

    public SchedulerService(ScheduledTaskRepository taskRepository,
                            NewsService newsService,
                            WeatherService weatherService,
                            @Lazy LlmService llmService) {
        this.taskRepository = taskRepository;
        this.newsService = newsService;
        this.weatherService = weatherService;
        this.llmService = llmService;
    }

    private ScheduledExecutorService executor;
    private final Map<String, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledTaskEntity> taskEntities = new ConcurrentHashMap<>();

    /** 回调接口，用于推送消息给用户 */
    public interface MessageSender {
        void send(String userId, String message);
    }

    private MessageSender messageSender;

    public void setMessageSender(MessageSender sender) {
        this.messageSender = sender;
    }

    @PostConstruct
    public void init() {
        executor = Executors.newScheduledThreadPool(4);
        // 启动时恢复未完成的任务
        recoverTasks();
    }

    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
    }

    /**
     * 创建一次性提醒
     */
    public String createReminder(String userId, long delaySeconds, String message) {
        String taskId = UUID.randomUUID().toString();

        ScheduledTaskEntity entity = new ScheduledTaskEntity();
        entity.setTaskId(taskId);
        entity.setUserId(userId);
        entity.setTaskType("remind");
        entity.setDescription(message);
        entity.setDelaySeconds(delaySeconds);
        entity.setStatus("active");
        taskRepository.save(entity);

        scheduleReminder(taskId, userId, delaySeconds, message);
        return taskId;
    }

    /**
     * 创建周期任务
     */
    public String createRepeatingTask(String userId, long intervalSeconds, String description) {
        String taskId = UUID.randomUUID().toString();

        ScheduledTaskEntity entity = new ScheduledTaskEntity();
        entity.setTaskId(taskId);
        entity.setUserId(userId);
        entity.setTaskType("repeat");
        entity.setDescription(description);
        entity.setIntervalSeconds(intervalSeconds);
        entity.setStatus("active");
        taskRepository.save(entity);

        scheduleRepeatingTask(taskId, userId, intervalSeconds, description);
        return taskId;
    }

    /**
     * 取消任务
     */
    public boolean cancelTask(String taskId) {
        ScheduledFuture<?> future = activeTasks.remove(taskId);
        taskEntities.remove(taskId);

        ScheduledTaskEntity entity = taskRepository.findById(taskId).orElse(null);
        if (entity != null) {
            entity.setStatus("cancelled");
            taskRepository.save(entity);
        }

        if (future != null) {
            future.cancel(false);
            log.info("任务已取消: taskId={}", taskId);
            return true;
        }
        return entity != null;
    }

    /**
     * 取消用户的所有任务
     */
    public int cancelAllTasks(String userId) {
        List<ScheduledTaskEntity> tasks = taskRepository.findByUserIdAndStatus(userId, "active");
        int count = 0;
        for (ScheduledTaskEntity task : tasks) {
            cancelTask(task.getTaskId());
            count++;
        }
        return count;
    }

    /**
     * 列出用户的活跃任务
     */
    public List<ScheduledTaskEntity> listActiveTasks(String userId) {
        return taskRepository.findByUserIdAndStatus(userId, "active");
    }

    /**
     * 恢复未完成的任务（应用启动时调用）
     */
    private void recoverTasks() {
        List<ScheduledTaskEntity> activeTasksList = taskRepository.findByStatus("active");
        log.info("恢复 {} 个未完成的定时任务", activeTasksList.size());

        for (ScheduledTaskEntity entity : activeTasksList) {
            try {
                if ("remind".equals(entity.getTaskType())) {
                    // 对于提醒任务，如果已经过了提醒时间，立即执行
                    long delay = entity.getDelaySeconds() != null ? entity.getDelaySeconds() : 0;
                    scheduleReminder(entity.getTaskId(), entity.getUserId(), delay, entity.getDescription());
                } else if ("repeat".equals(entity.getTaskType())) {
                    long interval = entity.getIntervalSeconds() != null ? entity.getIntervalSeconds() : 300;
                    scheduleRepeatingTask(entity.getTaskId(), entity.getUserId(), interval, entity.getDescription());
                }
            } catch (Exception e) {
                log.error("恢复任务失败: taskId={}", entity.getTaskId(), e);
            }
        }
    }

    private void scheduleReminder(String taskId, String userId, long delaySeconds, String message) {
        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                String logPrefix = ThinkingLogBuilder.buildSimple(message,
                        "⏰ 定时提醒触发",
                        "💡 决策：发送提醒消息给用户");
                String fullMessage = logPrefix + "\n\n💬 提醒：\n" + message;
                sendMessage(userId, fullMessage);

                // 标记为完成
                ScheduledTaskEntity entity = taskRepository.findById(taskId).orElse(null);
                if (entity != null) {
                    entity.setStatus("completed");
                    taskRepository.save(entity);
                }
                activeTasks.remove(taskId);
                taskEntities.remove(taskId);
            } catch (Exception e) {
                log.error("提醒执行失败: taskId={}", taskId, e);
            }
        }, delaySeconds, TimeUnit.SECONDS);

        activeTasks.put(taskId, future);
        log.info("已创建提醒: taskId={}, userId={}, delay={}s, message={}", taskId, userId, delaySeconds, message);
    }

    private void scheduleRepeatingTask(String taskId, String userId, long intervalSeconds, String description) {
        // 首次执行延迟 5 秒，后续按间隔执行
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            try {
                executeRepeatingTask(taskId, userId, description);
            } catch (Exception e) {
                log.error("周期任务执行失败: taskId={}", taskId, e);
            }
        }, 5, intervalSeconds, TimeUnit.SECONDS);

        activeTasks.put(taskId, future);
        log.info("已创建周期任务: taskId={}, userId={}, interval={}s, desc={}", taskId, userId, intervalSeconds, description);
    }

    /**
     * 执行周期任务 — 获取原始数据后交给 LLM 总结
     */
    private void executeRepeatingTask(String taskId, String userId, String description) {
        ThinkingLogBuilder logBuilder = new ThinkingLogBuilder(description);
        logBuilder.addStep("🔄", "周期任务触发：" + description);

        String rawData;
        String techInfo;

        // 根据描述路由到对应服务
        if (containsAny(description, "新闻", "热搜", "热榜", "头条")) {
            String source = detectNewsSource(description);
            logBuilder.addStep("🔍", "识别意图：新闻查询，来源=" + source);
            rawData = newsService.getNews(source, 10);
            techInfo = "调用 60s.viki.moe API 获取" + source + "热榜数据";
        } else if (containsAny(description, "天气")) {
            String city = extractCity(description);
            logBuilder.addStep("🔍", "识别意图：天气查询，城市=" + city);
            rawData = weatherService.getWeather(city);
            techInfo = "调用心知天气 API";
        } else {
            // 其他任务交给 LLM 处理
            logBuilder.addStep("🔍", "识别意图：通用任务，交由 LLM 处理");
            rawData = llmService.chat(userId, "请执行以下任务：" + description);
            techInfo = "调用 DeepSeek LLM 处理";
        }

        logBuilder.addToolCall("周期任务执行", description, techInfo, truncate(rawData, 100));

        // 交给 LLM 总结
        String summary;
        try {
            String summaryPrompt = "请对以下内容做简要总结，用简洁的中文，控制在200字以内：\n\n" + rawData;
            summary = llmService.chat(userId, summaryPrompt);
            logBuilder.addStep("🤖", "LLM 总结完成");
        } catch (Exception e) {
            summary = rawData;
            logBuilder.addStep("⚠️", "LLM 总结失败，返回原始数据");
        }

        String finalMessage = logBuilder.build(summary);
        sendMessage(userId, finalMessage);
    }

    private void sendMessage(String userId, String message) {
        if (messageSender != null) {
            try {
                messageSender.send(userId, message);
            } catch (Exception e) {
                log.error("推送消息失败: userId={}", userId, e);
            }
        }
    }

    private String detectNewsSource(String description) {
        if (description.contains("微博")) return "weibo";
        if (description.contains("头条") || description.contains("今日")) return "toutiao";
        if (description.contains("知乎")) return "zhihu";
        if (description.contains("百度")) return "baidu";
        if (description.contains("抖音")) return "douyin";
        if (description.contains("60秒") || description.contains("每日")) return "60s";
        return "weibo"; // 默认微博
    }

    private String extractCity(String description) {
        // 简单提取城市名，去掉"天气"等关键词
        String city = description.replaceAll("天气|查询|帮我|看看|一下|的|每|分钟|小时", "").trim();
        return city.isEmpty() ? "北京" : city;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
