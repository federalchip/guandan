package com.guandan.api;

import com.guandan.api.dto.DtoMapper;
import com.guandan.api.dto.ErrorBody;
import com.guandan.api.dto.MoveView;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.Move;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一错误映射：GameRuleException.code → HTTP 状态与 {code, message, details}；Jackson/Bean Validation
 * 结构错误 → 422 SCHEMA_INVALID；其余未预期异常 → 500 INTERNAL_ERROR（批次已在服务层回滚）。
 * 域级 CARD_ID_OUT_OF_RANGE（ID 范围）按合同规范化为 422 SCHEMA_INVALID。响应不泄露 traceback、seed 或他人手牌。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Map<String, HttpStatus> STATUS_BY_CODE = Map.ofEntries(
            Map.entry("DUPLICATE_CARD_ID", HttpStatus.BAD_REQUEST),
            Map.entry("CARD_NOT_OWNED", HttpStatus.BAD_REQUEST),
            Map.entry("INVALID_MOVE", HttpStatus.BAD_REQUEST),
            Map.entry("INVALID_DECLARATION", HttpStatus.BAD_REQUEST),
            Map.entry("PASS_NOT_ALLOWED", HttpStatus.BAD_REQUEST),
            Map.entry("SESSION_INVALID", HttpStatus.UNAUTHORIZED),
            Map.entry("SESSION_FORBIDDEN", HttpStatus.FORBIDDEN),
            Map.entry("GAME_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("VERSION_CONFLICT", HttpStatus.CONFLICT),
            Map.entry("NOT_YOUR_TURN", HttpStatus.CONFLICT),
            Map.entry("GAME_FINISHED", HttpStatus.CONFLICT),
            Map.entry("REQUEST_ID_REUSED", HttpStatus.CONFLICT),
            Map.entry("AMBIGUOUS_DECLARATION", HttpStatus.CONFLICT),
            Map.entry("SCHEMA_INVALID", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("BOT_STEP_LIMIT", HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR));

    @ExceptionHandler(GameRuleException.class)
    public ResponseEntity<ErrorBody> handleGameRule(GameRuleException ex) {
        String code = ex.code();
        HttpStatus status;
        if ("CARD_ID_OUT_OF_RANGE".equals(code)) {
            code = "SCHEMA_INVALID";
            status = HttpStatus.UNPROCESSABLE_ENTITY;
        } else {
            status = STATUS_BY_CODE.getOrDefault(code, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return ResponseEntity.status(status).body(new ErrorBody(code, ex.getMessage(), sanitize(ex.details())));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ErrorBody> handleSchema(Exception ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorBody("SCHEMA_INVALID", "请求结构、字段类型或缺失/多余字段不合法", Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorBody("INTERNAL_ERROR", "内部错误", Map.of()));
    }

    /** 把 details 中的域对象（AMBIGUOUS_DECLARATION 的 choices）映射为白名单 DTO，其余原样透传。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitize(Map<String, Object> details) {
        if (details == null || !details.containsKey("choices")) {
            return details == null ? Map.of() : details;
        }
        Map<String, Object> out = new LinkedHashMap<>(details);
        out.put("choices", DtoMapper.toMoveViews((List<Move>) details.get("choices")));
        return out;
    }
}
