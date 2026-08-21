package org.example.common.api;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SseMessage {
    private String type;
    private Object data;

    public static SseMessage content(String data) {
        return create("content", data);
    }

    public static SseMessage error(ApiError error) {
        return create("error", error);
    }

    public static SseMessage done() {
        return create("done", null);
    }

    private static SseMessage create(String type, Object data) {
        SseMessage message = new SseMessage();
        message.setType(type);
        message.setData(data);
        return message;
    }
}
