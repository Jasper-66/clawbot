package com.example.clawbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次性定时提醒业务服务，负责提醒的创建、内存存储和到期查询。
 *
 * <p>以 {@link ConcurrentHashMap} 作为内存存储，应用重启后尚未发送的提醒会丢失。
 * 如需持久化存储可替换为 Redis/数据库实现。</p>
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li><b>创建提醒</b> — 校验参数合法性，分配 UUID，存入内存 Map</li>
 *   <li><b>查询到期提醒</b> — 筛选出所有触发时间 <= 当前时间的未发送提醒</li>
 *   <li><b>标记已发送</b> — 从 Map 中移除，防止重复发送</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>{@link ConcurrentHashMap} 保证并发安全，多个线程可同时读写。
 * 定时任务线程（{@link org.springframework.scheduling.annotation.Scheduled}）
 * 和消息轮询线程共享此实例。</p>
 *
 * <h3>数据模型</h3>
 * <p>{@link ReminderTask} 采用 Java 16 record 定义，自动生成构造器、getter、
 * equals/hashCode、toString。{@link ReminderType} 枚举定义三种提醒方式：</p>
 * <ul>
 *   <li>{@code TEXT} — 文本消息提醒</li>
 *   <li>{@code VOICE} — 语音播报提醒</li>
 *   <li>{@code BOTH} — 文本 + 语音双重提醒</li>
 * </ul>
 *
 * @see com.example.clawbot.tool.ReminderTool
 * @see com.example.clawbot.service.WeChatBotService#sendDueReminders()
 */
@Slf4j
@Service
public class ReminderService {

    /**
     * 提醒任务存储表。
     *
     * <p>Key = {@link ReminderTask#id()}（UUID 字符串），Value = {@link ReminderTask} 实例。
     * 使用 ConcurrentHashMap 而非普通 HashMap，因为定时发送线程和消息处理线程会并发访问。</p>
     */
    private final ConcurrentHashMap<String, ReminderTask> reminders = new ConcurrentHashMap<>();

    /**
     * 创建一次性定时提醒。
     *
     * <p>参数校验规则（不符合将抛出 {@link IllegalArgumentException}）：</p>
     * <ul>
     *   <li>userId — 非空、非空白</li>
     *   <li>content — 非空、非空白</li>
     *   <li>triggerAt — 非空，且晚于当前时间（不允许过去时间）</li>
     *   <li>type — 非空</li>
     * </ul>
     *
     * <p>校验通过后生成全局唯一 ID（{@link UUID#randomUUID()}），
     * 构造 {@link ReminderTask} record 并存入内存 Map。</p>
     *
     * @param userId    用户微信 ID，用于发送提醒时定位收信人
     * @param content   提醒内容文本，将原样发送给用户
     * @param triggerAt 提醒触发时间（ISO 8601 Instant），精确到毫秒
     * @param type      提醒方式（TEXT / VOICE / BOTH）
     * @return 已存储的 ReminderTask 实例（包含生成的 id）
     * @throws IllegalArgumentException 任一参数不合法时抛出
     */
    public ReminderTask createReminder(String userId, String content,
                                       Instant triggerAt, ReminderType type) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("无法识别当前微信用户");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("提醒内容不能为空");
        }
        if (triggerAt == null || !triggerAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("提醒时间必须晚于当前时间");
        }
        if (type == null) {
            throw new IllegalArgumentException("提醒方式不能为空");
        }

        String id = UUID.randomUUID().toString();
        ReminderTask task = new ReminderTask(id, userId, content, triggerAt, type);
        reminders.put(id, task);
        log.info("创建提醒: id={}, userId={}, triggerAt={}, type={}",
                id, userId, triggerAt, type);
        return task;
    }

    /**
     * 获取所有到期的提醒任务（触发时间 ≤ 当前时间）。
     *
     * <p>简化的重载方法，实际委托给 {@link #getDueReminders(Instant)} 并将当前时间作为参数。</p>
     *
     * @return 按触发时间升序排列的到期提醒列表，无到期提醒时返回空列表
     */
    public List<ReminderTask> getDueReminders() {
        return getDueReminders(Instant.now());
    }

    /**
     * 获取所有到期的提醒任务（触发时间 ≤ 指定时间）。
     *
     * <p>通过 Stream API 对 Map values 执行两阶段过滤：</p>
     * <ol>
     *   <li>{@code filter} — 保留触发时间不晚于指定时间的任务</li>
     *   <li>{@code sorted} — 按触发时间升序排列（先到期的排在前面）</li>
     *   <li>{@code toList} — 收集为不可变 List</li>
     * </ol>
     *
     * <p>返回的列表是调用时刻的快照。后续新的到期任务不会被包含，
     * 需要通过下一次调用来获取。这是正确的行为，因为定时任务每 10 秒执行一次。</p>
     *
     * @param now 用于比较的基准时间（通常为当前时间）
     * @return 按触发时间升序排列的到期提醒列表
     */
    public List<ReminderTask> getDueReminders(Instant now) {
        return reminders.values().stream()
                .filter(task -> !task.triggerAt().isAfter(now))
                .sorted(Comparator.comparing(ReminderTask::triggerAt))
                .toList();
    }

    /**
     * 标记提醒已发送 — 从存储中移除。
     *
     * <p>对应 WeChatBotService.sendDueReminders() 的发送操作，
     * 发送成功后调用此方法清理已完成的提醒。如果传入的 ID 不存在（已被其他线程移除），
     * remove 操作静默返回 null，不会抛出异常。</p>
     *
     * @param reminderId 提醒的唯一标识（{@link ReminderTask#id()}）
     */
    public void markSent(String reminderId) {
        reminders.remove(reminderId);
    }

    /**
     * 提醒方式枚举。
     *
     * <p>定义用户期望的提醒展示形式，由 LLM 根据用户意图选择，
     * 对应 ReminderTool 工具定义中的 {@code reminder_type} 参数可选值。</p>
     */
    public enum ReminderType {
        /** 仅发送文本消息 */
        TEXT,
        /** 仅发送语音播报 */
        VOICE,
        /** 同时发送文本和语音 */
        BOTH
    }

    /**
     * 提醒任务数据模型（Java 16 record）。
     *
     * <p>record 自动生成：全部参数构造器、{@code id()/userId()/...} 访问方法、
     * {@code equals()/hashCode()}、{@code toString()}。</p>
     *
     * @param id        全局唯一标识（UUID 字符串）
     * @param userId    目标用户微信 ID
     * @param content   提醒内容文本
     * @param triggerAt 触发时间点
     * @param type      提醒方式
     */
    public record ReminderTask(
            String id,
            String userId,
            String content,
            Instant triggerAt,
            ReminderType type
    ) {
    }
}
