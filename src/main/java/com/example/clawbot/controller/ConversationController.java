package com.example.clawbot.controller;

import com.example.clawbot.exception.Result;
import com.example.clawbot.repository.ConversationRepository;
import com.example.clawbot.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        // 直接查所有会话（按更新时间倒序）
        List<Map<String, Object>> list = conversationRepository.findAll();
        return Result.success(list);
    }

    @GetMapping("/{id}/messages")
    public Result<List<Map<String, Object>>> messages(@PathVariable String id) {
        List<Map<String, Object>> messages = messageRepository.findAllByConversationId(id);
        return Result.success(messages);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        conversationRepository.deleteById(id);
        return Result.success(null);
    }
}
