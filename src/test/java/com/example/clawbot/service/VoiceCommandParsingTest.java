package com.example.clawbot.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceCommandParsingTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "帮我换个声音",
            "我想切换音色",
            "把声音换成Ethan",
            "换成Ethan的声音",
            "使用Cherry音色",
            "选择芊悦音色",
            "声音列表",
            "支持哪些声音",
            "现在是什么音色"
    })
    void shouldRecognizeNaturalVoiceCommands(String command) {
        assertThat(WeChatBotService.isVoiceCommand(command)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "把声音换成Ethan, Ethan",
            "换成Ethan的声音, Ethan",
            "使用Cherry音色, Cherry",
            "选择芊悦音色, 芊悦",
            "请帮我切换音色为晨煦, 晨煦",
            "帮我换个声音, ''"
    })
    void shouldExtractVoiceNameFromNaturalCommands(String command, String expectedVoice) {
        assertThat(WeChatBotService.extractVoiceName(command)).isEqualTo(expectedVoice);
    }
}
