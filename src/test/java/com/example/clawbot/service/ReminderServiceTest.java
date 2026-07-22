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
}
