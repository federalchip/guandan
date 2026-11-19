package com.guandan.api.dto;

/** GET /commands 终态：完成，result 与原同步 CommandResponse 等价。 */
public record CommandCompleted(String status, CommandResponse result) {
    public CommandCompleted(CommandResponse result) {
        this("COMPLETED", result);
    }
}
