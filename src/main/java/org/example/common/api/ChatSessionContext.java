package org.example.common.api;

/** 当前请求关联的聊天会话，用于把工具审计记录关联回会话。 */
public final class ChatSessionContext {
    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    private ChatSessionContext() {
    }

    public static void set(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    public static String get() {
        return SESSION_ID.get();
    }

    public static void clear() {
        SESSION_ID.remove();
    }
}
