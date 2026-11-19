package com.guandan.api;

import com.guandan.api.dto.CommandCompleted;
import com.guandan.api.dto.CommandFailed;
import com.guandan.api.dto.CommandRequest;
import com.guandan.api.dto.CommandResponse;
import com.guandan.api.dto.CreatedGame;
import com.guandan.api.dto.CreateGameRequest;
import com.guandan.api.dto.ErrorBody;
import com.guandan.api.dto.GameView;
import com.guandan.api.dto.LegalActionsResponse;
import com.guandan.api.dto.PendingResponse;
import com.guandan.api.dto.ResolveRequest;
import com.guandan.api.dto.ResolveResponse;
import com.guandan.service.CommandOutcome;
import com.guandan.service.GameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 同步 HTTP 路由，统一 /api/v1 前缀；除创建外使用 {@code Authorization: Bearer <session_token>}。
 * 路由不接收客户端 player_id（真人固定座位 0）。控制器只做请求/响应，业务在 {@link GameService}。
 * 会话鉴权由 {@link SessionAuthInterceptor} 在请求体解析前前置完成，服务层再次校验（防御性、且供单元测试）。
 */
@RestController
@RequestMapping("/api/v1")
public class GameController {

    private final GameService service;

    public GameController(GameService service) {
        this.service = service;
    }

    @PostMapping("/games")
    public ResponseEntity<CreatedGame> createGame(
            @RequestBody(required = false) CreateGameRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createGame());
    }

    @GetMapping("/games/{id}")
    public GameView getGame(@PathVariable("id") String id,
                            @RequestHeader(value = "Authorization", required = false) String auth) {
        return service.getGame(id, BearerTokens.token(auth));
    }

    @GetMapping("/games/{id}/legal-actions")
    public LegalActionsResponse legalActions(@PathVariable("id") String id,
                                             @RequestHeader(value = "Authorization", required = false) String auth) {
        return service.legalActions(id, BearerTokens.token(auth));
    }

    @PostMapping("/games/{id}/resolve")
    public ResolveResponse resolve(@PathVariable("id") String id,
                                   @RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestBody ResolveRequest body) {
        return service.resolve(id, BearerTokens.token(auth), body);
    }

    @PostMapping("/games/{id}/actions")
    @Operation(summary = "提交真人命令：同步完成 200 CommandResponse；异步登记 202；同 ID 终态失败 200 CommandFailed")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "同步完成或同 ID 终态失败",
                    content = @Content(schema = @Schema(oneOf = {CommandResponse.class, CommandFailed.class}))),
            @ApiResponse(responseCode = "202", description = "已登记，机器人批次处理中",
                    content = @Content(schema = @Schema(implementation = PendingResponse.class)))
    })
    public ResponseEntity<Object> submit(@PathVariable("id") String id,
                                         @RequestHeader(value = "Authorization", required = false) String auth,
                                         @RequestBody CommandRequest body) {
        CommandOutcome outcome = service.submitCommand(id, BearerTokens.token(auth), body);
        if (outcome instanceof CommandOutcome.Completed c) {
            return ResponseEntity.ok(c.response());
        }
        if (outcome instanceof CommandOutcome.Pending p) {
            return accepted(p);
        }
        CommandOutcome.Failed f = (CommandOutcome.Failed) outcome;
        return ResponseEntity.ok(new CommandFailed(f.code(), f.message()));
    }

    @GetMapping("/games/{id}/commands/{requestId}")
    @Operation(summary = "查询命令状态：202 处理中；200 终态（COMPLETED/FAILED）；404 未知命令或旧局")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "终态",
                    content = @Content(schema = @Schema(oneOf = {CommandCompleted.class, CommandFailed.class}))),
            @ApiResponse(responseCode = "202", description = "处理中",
                    content = @Content(schema = @Schema(implementation = PendingResponse.class))),
            @ApiResponse(responseCode = "404", description = "COMMAND_NOT_FOUND / GAME_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ErrorBody.class)))
    })
    public ResponseEntity<Object> commandStatus(@PathVariable("id") String id,
                                                @PathVariable("requestId") String requestId,
                                                @RequestHeader(value = "Authorization", required = false) String auth) {
        CommandOutcome outcome = service.commandStatus(id, BearerTokens.token(auth), requestId);
        if (outcome instanceof CommandOutcome.Completed c) {
            return ResponseEntity.ok(new CommandCompleted(c.response()));
        }
        if (outcome instanceof CommandOutcome.Pending p) {
            return accepted(p);
        }
        CommandOutcome.Failed f = (CommandOutcome.Failed) outcome;
        return ResponseEntity.ok(new CommandFailed(f.code(), f.message()));
    }

    private static ResponseEntity<Object> accepted(CommandOutcome.Pending p) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new PendingResponse(p.requestId(), p.pollAfterMillis()));
    }
}
