package cn.travelbao.common.web;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(cn.travelbao.common.exception.ServiceProblem.class)
    ResponseEntity<?> problem(cn.travelbao.common.exception.ServiceProblem problem) {
        int status = switch (problem.code()) {
            case "NOT_FOUND" -> 404;
            case "VERSION_CONFLICT", "CONVERSATION_BUSY", "KNOWLEDGE_BUSY", "INDEX_REBUILD_REQUIRED", "AGENT_BUSY" -> 409;
            case "INVALID_REQUEST", "INVALID_ITINERARY", "INVALID_KNOWLEDGE" -> 400;
            default -> 502;
        };
        return ResponseEntity.status(status).body(Map.of("code", problem.code(), "message", problem.getMessage()));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge() { return ResponseEntity.status(413).body(Map.of("code","FILE_TOO_LARGE","message","上传文件不能超过256KB。")); }
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    ResponseEntity<?> missingParameter() { return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","资料元数据或上传文件不完整。")); }
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    ResponseEntity<?> missingPart() { return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","请选择要上传的资料文件。")); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .findFirst().map(e -> e.getDefaultMessage()).orElse("请求参数不正确");
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_JSON", "message", "请求内容格式不正确"));
    }
}
