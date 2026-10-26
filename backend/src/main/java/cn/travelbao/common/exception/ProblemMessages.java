package cn.travelbao.common.exception;

import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

public final class ProblemMessages {
    private static final Pattern AI_HTTP_STATUS = Pattern.compile("^HTTP\\s+(\\d{3})\\b");
    private ProblemMessages() { }

    public static ServiceProblem describe(Throwable error) {
        Throwable current = error;
        for (int i = 0; current != null && i < 12; i++, current = current.getCause()) {
            if (current instanceof ServiceProblem problem) return problem;
            if (current instanceof TimeoutException || current instanceof java.net.SocketTimeoutException) {
                return new ServiceProblem("UPSTREAM_TIMEOUT", "等待服务响应超时，请稍后重试。");
            }
            int status = current instanceof RestClientResponseException ex ? ex.getStatusCode().value()
                    : current instanceof WebClientResponseException ex ? ex.getStatusCode().value() : 0;
            if (status == 0 && (current instanceof NonTransientAiException || current instanceof TransientAiException)) {
                var match = AI_HTTP_STATUS.matcher(current.getMessage() == null ? "" : current.getMessage());
                if (match.find()) status = Integer.parseInt(match.group(1));
            }
            if (status == 401 || status == 403) {
                return new ServiceProblem("MODEL_AUTH_FAILED", "模型认证失败，请检查本机密钥和账户权限。");
            }
            if (status == 402) return new ServiceProblem("MODEL_BALANCE", "模型账户余额不足，请检查账户状态。");
            if (status == 429) return new ServiceProblem("MODEL_RATE_LIMITED", "模型服务繁忙或达到调用限额，请稍后重试。");
            if (status == 404) return new ServiceProblem("MODEL_NOT_FOUND", "模型或服务地址不可用，请检查模型名称和地址。");
        }
        // Do not return provider response bodies: they may contain request details or credentials.
        return new ServiceProblem("UPSTREAM_UNAVAILABLE", "暂时无法连接服务，请检查网络和服务状态后重试。");
    }
}
