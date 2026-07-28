package com.example.clawbot.tool;

/**
 * 提醒工具上下文持有者 — 通过 ThreadLocal 传递当前用户 ID。
 */
public class RemindContextHolder {
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    public static void setUserId(String userId) { USER_ID.set(userId); }
    public static String getUserId() { return USER_ID.get(); }
    public static void clear() { USER_ID.remove(); }
}
