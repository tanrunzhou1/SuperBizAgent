package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * 统一大模型接入配置。provider 支持 dashscope、deepseek 和 openai-compatible。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.model")
public class AiModelProperties {
    /** 模型供应商：dashscope、deepseek 或 openai-compatible。 */
    private String provider = "dashscope";

    /** Responses API 供应商 API Key。为空时按 provider 读取对应环境变量。 */
    private String apiKey;

    /** Responses API 的 base URL；为空时按 provider 使用厂商默认地址。 */
    private String baseUrl;

    /** 模型名称。为空时由 ChatService 按 provider 使用默认模型。 */
    private String model;

    /** Responses API 请求超时时间。 */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration timeout = Duration.ofSeconds(180);

}
