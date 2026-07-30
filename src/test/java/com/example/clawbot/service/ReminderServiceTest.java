package com.example.clawbot.service;

import com.example.clawbot.repository.ReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReminderServiceTest {

    private ReminderRepository reminderRepository;
    private ReminderService reminderService;

    // 模拟数据库存储
    private final List<ReminderRepository.ReminderRow> store = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store.clear();
        reminderRepository = mock(ReminderRepository.class);
        reminderService = new ReminderService(reminderRepository);

        // 模拟 insert：存入 store
        doAnswer(inv -> {
            String id = inv.getArgument(0);
            String userId = inv.getArgument(1);
            String content = inv.getArgument(2);
            Instant triggerAt = inv.getArgument(3);
            String type = inv.getArgument(4);
            boolean periodic = inv.getArgument(5);
            long interval = inv.getArgument(6);
            store.add(new ReminderRepository.ReminderRow(id, userId, content, triggerAt, type, periodic, interval, "pending"));
            return null;
        }).when(reminderRepository).insert(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyLong());

        // 模拟 findAllPending
        when(reminderRepository.findAllPending()).thenAnswer(inv ->
                store.stream().filter(r -> "pending".equals(r.status())).toList());

        // 模拟 findPendingByTriggerAtBefore
        when(reminderRepository.findPendingByTriggerAtBefore(any())).thenAnswer(inv -> {
            Instant now = inv.getArgument(0);
            return store.stream()
                    .filter(r -> "pending".equals(r.status()) && !r.triggerAt().isAfter(now))
                    .toList();
        });

        // 模拟 markSent
        doAnswer(inv -> {
            String id = inv.getArgument(0);
            store.stream().filter(r -> r.id().equals(id)).findFirst()
                    .ifPresent(r -> {
                        int idx = store.indexOf(r);
                        store.set(idx, new ReminderRepository.ReminderRow(
                                r.id(), r.userId(), r.content(), r.triggerAt(),
                                r.reminderType(), r.periodic(), r.intervalSeconds(), "sent"));
                    });
            return null;
        }).when(reminderRepository).markSent(anyString());

        // 模拟 updateTriggerAt
        doAnswer(inv -> {
            String id = inv.getArgument(0);
            Instant next = inv.getArgument(1);
            store.stream().filter(r -> r.id().equals(id)).findFirst()
                    .ifPresent(r -> {
                        int idx = store.indexOf(r);
                        store.set(idx, new ReminderRepository.ReminderRow(
                                r.id(), r.userId(), r.content(), next,
                                r.reminderType(), r.periodic(), r.intervalSeconds(), r.status()));
                    });
            return null;
        }).when(reminderRepository).updateTriggerAt(anyString(), any());
    }

    @Test
    void shouldCreateAndReturnDueVoiceReminder() {
        Instant triggerAt = Instant.now().plusSeconds(300);
        reminderService.createReminder(
                "user-1", "参加会议", triggerAt, ReminderService.ReminderType.VOICE);

        assertThat(reminderService.getDueReminders(Instant.now())).isEmpty();

        List<ReminderService.ReminderTask> dueReminders =
                reminderService.getDueReminders(triggerAt.plusSeconds(1));
        assertThat(dueReminders).hasSize(1);
        assertThat(dueReminders.get(0).userId()).isEqualTo("user-1");
        assertThat(dueReminders.get(0).content()).isEqualTo("参加会议");
        assertThat(dueReminders.get(0).type()).isEqualTo(ReminderService.ReminderType.VOICE);

        reminderService.markSent(dueReminders.get(0).id());
        assertThat(reminderService.getDueReminders(triggerAt.plusSeconds(1))).isEmpty();
    }

    @Test
    void shouldRejectPastReminder() {
        assertThatThrownBy(() -> reminderService.createReminder(
                "user-1", "喝水", Instant.now().minusSeconds(60),
                ReminderService.ReminderType.TEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("晚于当前时间");
    }

    @Test
    void shouldCreatePeriodicReminderAndReschedule() {
        Instant firstTrigger = Instant.now().plusSeconds(120);
        ReminderService.ReminderTask task = reminderService.createPeriodicReminder(
                "user-1", "该喝水了", firstTrigger,
                ReminderService.ReminderType.TEXT, 300);

        assertThat(task.periodic()).isTrue();
        assertThat(task.intervalSeconds()).isEqualTo(300);
        assertThat(task.content()).isEqualTo("该喝水了");

        // 尚未到期
        assertThat(reminderService.getDueReminders(Instant.now())).isEmpty();

        // 到期后可查询到
        List<ReminderService.ReminderTask> due =
                reminderService.getDueReminders(firstTrigger.plusSeconds(1));
        assertThat(due).hasSize(1);
        assertThat(due.get(0).id()).isEqualTo(task.id());
    }

    @Test
    void shouldReschedulePeriodicReminder() {
        Instant firstTrigger = Instant.now().plusSeconds(60);
        ReminderService.ReminderTask task = reminderService.createPeriodicReminder(
                "user-1", "站起来活动", firstTrigger,
                ReminderService.ReminderType.VOICE, 600);

        // 模拟到期后重新调度
        reminderService.reschedule(task.id());

        // 重新调度后，下次触发时间应在未来
        List<ReminderService.ReminderTask> dueNow =
                reminderService.getDueReminders(Instant.now());
        assertThat(dueNow).isEmpty();

        // 新触发时间应在大约 600 秒后
        List<ReminderService.ReminderTask> dueLater =
                reminderService.getDueReminders(Instant.now().plusSeconds(600 + 5));
        assertThat(dueLater).hasSize(1);
        assertThat(dueLater.get(0).id()).isEqualTo(task.id());
    }

    @Test
    void shouldRejectTooShortInterval() {
        assertThatThrownBy(() -> reminderService.createPeriodicReminder(
                "user-1", "提醒", Instant.now().plusSeconds(120),
                ReminderService.ReminderType.TEXT, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("间隔");
    }

    @Test
    void shouldNotRescheduleNonPeriodicReminder() {
        Instant triggerAt = Instant.now().plusSeconds(300);
        ReminderService.ReminderTask task = reminderService.createReminder(
                "user-1", "一次性", triggerAt, ReminderService.ReminderType.TEXT);

        // 对一次性提醒调用 reschedule 应静默跳过
        reminderService.reschedule(task.id());

        // 提醒仍在，未受影响
        List<ReminderService.ReminderTask> due =
                reminderService.getDueReminders(triggerAt.plusSeconds(1));
        assertThat(due).hasSize(1);
        assertThat(due.get(0).periodic()).isFalse();
    }

    @Test
    void shouldMarkSentRemoveOneTimeButKeepPeriodicAfterReschedule() {
        // 一次性提醒：markSent 后应被移除
        Instant trigger = Instant.now().plusSeconds(60);
        ReminderService.ReminderTask oneTime = reminderService.createReminder(
                "user-1", "一次性", trigger, ReminderService.ReminderType.TEXT);
        reminderService.markSent(oneTime.id());
        assertThat(reminderService.getDueReminders(trigger.plusSeconds(1))).isEmpty();

        // 周期性提醒：reschedule 后仍在
        ReminderService.ReminderTask periodic = reminderService.createPeriodicReminder(
                "user-1", "周期性", trigger, ReminderService.ReminderType.TEXT, 300);
        reminderService.reschedule(periodic.id());
        // 重新调度到未来，现在不应到期
        assertThat(reminderService.getDueReminders(Instant.now())).isEmpty();
    }
}
