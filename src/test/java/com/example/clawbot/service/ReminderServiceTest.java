package com.example.clawbot.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReminderServiceTest {

    private final ReminderService reminderService = new ReminderService();

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

        // 提醒仍在 Map 中，未受影响
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
