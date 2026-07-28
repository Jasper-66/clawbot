package com.example.clawbot.repository;

import com.example.clawbot.entity.MessageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息日志 Repository
 *
 * <p>Spring Data JPA 自动实现接口方法，支持三种查询方式：</p>
 * <ol>
 *   <li>方法名派生查询 — 如 findByUserIdOrderByCreateTimeDesc</li>
 *   <li>JPQL 查询 — @Query 注解，面向实体类</li>
 *   <li>原生 SQL 查询 — @Query(nativeQuery=true)，面向数据库表</li>
 * </ol>
 */
@Repository
public interface
MessageLogRepository extends JpaRepository<MessageLog, Long> {

    // ==================== 方法名派生查询 ====================
    /** 按用户查询，按时间倒序 */
    List<MessageLog> findByUserIdOrderByCreateTimeDesc(String userId);

    /** 按消息类型查询 */
    List<MessageLog> findByMsgType(String msgType);

    /** 按消息方向查询 */
    List<MessageLog> findByDirection(String direction);

    /** 统计用户消息数量 */
    long countByUserId(String userId);

    /** 按用户和类型查询 */
    List<MessageLog> findByUserIdAndMsgType(String userId, String msgType);

    /** 查询时间范围内的消息 */
    List<MessageLog> findByCreateTimeBetween(LocalDateTime start, LocalDateTime end);

    /** 按内容模糊查询（方法名派生） */
    List<MessageLog> findByContentContaining(String keyword);

    // ==================== JPQL 查询 ====================

    /** 查询用户最近 N 条消息（JPQL） */
    @Query("SELECT m FROM MessageLog m WHERE m.userId = :userId ORDER BY m.createTime DESC")
    List<MessageLog> findRecentByUserId(@Param("userId") String userId);

    /** 按类型统计数量（JPQL） */
    @Query("SELECT m.msgType, COUNT(m) FROM MessageLog m GROUP BY m.msgType")
    List<Object[]> countGroupByMsgType();

    /** 按用户统计数量，按数量降序（JPQL） */
    @Query("SELECT m.userId, COUNT(m) FROM MessageLog m GROUP BY m.userId ORDER BY COUNT(m) DESC")
    List<Object[]> countGroupByUserId();

    // ==================== 原生 SQL 查询 ====================

    /** 原生 SQL：查询最近 N 条消息 */
    @Query(value = "SELECT * FROM message_log ORDER BY create_time DESC LIMIT :limit", nativeQuery = true)
    List<MessageLog> findLatestNative(@Param("limit") int limit);

    /** 查询用户最近 N 条消息（按时间正序，用于构建对话历史） */
    @Query(value = "SELECT * FROM (SELECT * FROM message_log WHERE user_id = :userId ORDER BY create_time DESC LIMIT :limit) t ORDER BY create_time ASC", nativeQuery = true)
    List<MessageLog> findRecentByUserIdAsc(@Param("userId") String userId, @Param("limit") int limit);

    /** 原生 SQL：按关键词模糊搜索 */
    @Query(value = "SELECT * FROM message_log WHERE content LIKE '%' || :keyword || '%'", nativeQuery = true)
    List<MessageLog> searchByKeywordNative(@Param("keyword") String keyword);

    /** 原生 SQL：删除指定时间之前的消息 */
    @Modifying
    @Query(value = "DELETE FROM message_log WHERE create_time < :before", nativeQuery = true)
    int deleteByCreateTimeBefore(@Param("before") LocalDateTime before);

    /** 原生 SQL：获取表行数 */
    @Query(value = "SELECT COUNT(*) FROM message_log", nativeQuery = true)
    long getTotalCount();
}
