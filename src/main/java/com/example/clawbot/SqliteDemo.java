package com.example.clawbot;

import com.example.clawbot.entity.MessageLog;
import com.example.clawbot.repository.MessageLogRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 基础操作演示
 *
 * <p>启动时自动执行，验证建表、插入、查询、更新、删除等操作。
 * 演示三种查询方式：方法名派生、JPQL、原生 SQL。</p>
 */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
public class SqliteDemo implements CommandLineRunner {

    private final MessageLogRepository repository;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("========== SQLite CRUD 演示开始 ==========\n");

        // 清理旧的测试数据
        repository.deleteAll();
        log.info("[INIT] 已清空旧数据\n");

        // ── 1. 建表 ──
        // 表由 Hibernate 根据 @Entity 自动创建（ddl-auto=update）
        log.info("[1. 建表] message_log 表已由 Hibernate 自动创建（含索引 idx_user_id, idx_msg_type, idx_create_time）\n");

        // ── 2. 插入数据 ──
        log.info("[2. 插入数据]");

        // 单条插入
        MessageLog msg1 = repository.save(MessageLog.builder()
                .userId("wx_user_alice")
                .content("你好，今天天气怎么样？")
                .msgType("text")
                .direction("in")
                .build());
        log.info("  单条插入: id={}, content={}", msg1.getId(), msg1.getContent());

        // 批量插入
        List<MessageLog> batch = List.of(
                MessageLog.builder().userId("wx_user_alice").content("帮我画一只猫").msgType("text").direction("in").build(),
                MessageLog.builder().userId("wx_user_bob").content("你好啊").msgType("text").direction("in").build(),
                MessageLog.builder().userId("wx_user_alice").content("明天有雨吗").msgType("text").direction("in").build(),
                MessageLog.builder().userId("wx_user_carol").content("[图片消息]").msgType("image").direction("in").build(),
                MessageLog.builder().userId("wx_user_bob").content("朗读这段话").msgType("text").direction("in").build(),
                MessageLog.builder().userId("wx_user_alice").content("好的，收到").msgType("text").direction("out").build()
        );
        repository.saveAll(batch);
        log.info("  批量插入: {} 条记录\n", batch.size());

        // ── 3. 查询数据 ──
        log.info("[3. 查询数据]");

        // 3.1 按 ID 查询
        log.info("  --- 按 ID 查询 ---");
        Optional<MessageLog> found = repository.findById(msg1.getId());
        found.ifPresent(m -> log.info("  findById({}): userId={}, content={}", m.getId(), m.getUserId(), m.getContent()));

        // 3.2 查询全部
        log.info("\n  --- 查询全部 ---");
        List<MessageLog> all = repository.findAll();
        log.info("  findAll(): 共 {} 条记录", all.size());
        all.forEach(m -> log.info("    id={}, user={}, type={}, content={}",
                m.getId(), m.getUserId(), m.getMsgType(), truncate(m.getContent(), 20)));

        // 3.3 方法名派生查询
        log.info("\n  --- 方法名派生查询 ---");
        List<MessageLog> aliceMessages = repository.findByUserIdOrderByCreateTimeDesc("wx_user_alice");
        log.info("  findByUserId('wx_user_alice'): {} 条", aliceMessages.size());

        List<MessageLog> textMessages = repository.findByMsgType("text");
        log.info("  findByMsgType('text'): {} 条", textMessages.size());

        List<MessageLog> outMessages = repository.findByDirection("out");
        log.info("  findByDirection('out'): {} 条", outMessages.size());

        List<MessageLog> containsResult = repository.findByContentContaining("天气");
        log.info("  findByContentContaining('天气'): {} 条", containsResult.size());

        // 3.4 JPQL 查询
        log.info("\n  --- JPQL 查询 ---");
        List<MessageLog> recentAlice = repository.findRecentByUserId("wx_user_alice");
        log.info("  findRecentByUserId('wx_user_alice'): {} 条", recentAlice.size());

        List<Object[]> typeStats = repository.countGroupByMsgType();
        log.info("  countGroupByMsgType():");
        typeStats.forEach(row -> log.info("    type={}, count={}", row[0], row[1]));

        List<Object[]> userStats = repository.countGroupByUserId();
        log.info("  countGroupByUserId():");
        userStats.forEach(row -> log.info("    userId={}, count={}", row[0], row[1]));

        // 3.5 原生 SQL 查询
        log.info("\n  --- 原生 SQL 查询 ---");
        List<MessageLog> latest3 = repository.findLatestNative(3);
        log.info("  findLatestNative(3): {} 条", latest3.size());
        latest3.forEach(m -> log.info("    id={}, content={}", m.getId(), truncate(m.getContent(), 20)));

        List<MessageLog> searchResult = repository.searchByKeywordNative("你好");
        log.info("  searchByKeywordNative('你好'): {} 条", searchResult.size());

        long total = repository.getTotalCount();
        log.info("  getTotalCount(): {} 条\n", total);

        // ── 4. 更新数据 ──
        log.info("[4. 更新数据]");
        msg1.setContent("更新：今天晴天，气温25°C");
        repository.save(msg1);
        MessageLog updated = repository.findById(msg1.getId()).orElseThrow();
        log.info("  更新后: id={}, content={}", updated.getId(), updated.getContent());

        // 批量更新
        aliceMessages.forEach(m -> {
            if ("text".equals(m.getMsgType())) {
                m.setDirection("archived");
            }
        });
        repository.saveAll(aliceMessages);
        log.info("  批量更新 alice 的 text 消息 direction 为 'archived'\n");

        // ── 5. 删除数据 ──
        log.info("[5. 删除数据]");

        // 按 ID 删除
        repository.deleteById(msg1.getId());
        log.info("  deleteById({}): 删除成功", msg1.getId());

        // 按时间删除（删除 1 小时前的数据）
        int deleted = repository.deleteByCreateTimeBefore(LocalDateTime.now().minusHours(1));
        log.info("  deleteByCreateTimeBefore(1小时前): 删除 {} 条", deleted);

        long remaining = repository.getTotalCount();
        log.info("  剩余记录: {} 条", remaining);

        // 清理测试数据
        repository.deleteAll();
        log.info("  deleteAll(): 已清空所有测试数据");

        log.info("\n========== SQLite CRUD 演示完成 ==========");
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
