package com.kama.jchatmind.model.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * @TableName users
 */
@Data
@Builder
public class User {
    private String id;

    private String username;

    private String passwordHash;

    private Boolean enabled;

    private LocalDateTime createdAt;
}
