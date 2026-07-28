package com.example.clawbot.repository;

import com.example.clawbot.entity.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {
    
    List<ChatHistory> findByUserIdOrderByCreatedAtDesc(String userId);
    
    List<ChatHistory> findTop20ByUserIdOrderByCreatedAtDesc(String userId);
}
