package com.example.clawbot.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechServiceVoiceTest {

    private final SpeechService speechService = new SpeechService(new RestTemplate());

    @Test
    void shouldListAvailableVoicesWhenVoiceNameIsEmpty() {
        String result = speechService.setVoice("user-1", " ");

        assertThat(result)
                .contains("可用音色列表")
                .contains("Cherry（芊悦）")
                .contains("Ethan（晨煦）")
                .contains("切换音色Ethan");
    }

    @Test
    void shouldStoreVoicePreferenceForEachUser() {
        speechService.setVoice("user-1", "晨煦");
        speechService.setVoice("user-2", "Cherry");

        assertThat(speechService.getCurrentVoice("user-1")).startsWith("Ethan（晨煦");
        assertThat(speechService.getCurrentVoice("user-2")).startsWith("Cherry（芊悦");
    }
}
