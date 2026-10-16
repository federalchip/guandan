package com.guandan.api;

import com.guandan.api.dto.CommandRequest;
import com.guandan.api.dto.CommandResponse;
import com.guandan.api.dto.CreatedGame;
import com.guandan.api.dto.CreateGameRequest;
import com.guandan.api.dto.GameView;
import com.guandan.api.dto.LegalActionsResponse;
import com.guandan.api.dto.ResolveRequest;
import com.guandan.api.dto.ResolveResponse;
import com.guandan.service.GameService;
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
    public CommandResponse submit(@PathVariable("id") String id,
                                  @RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestBody CommandRequest body) {
        return service.submit(id, BearerTokens.token(auth), body);
    }
}
