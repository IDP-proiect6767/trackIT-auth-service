package com.trackit.auth.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "auth.jwt")
@Getter
@Setter
public class JwtProperties {

    private String secret;
    private long accessTtlSeconds = 900;
    private long refreshTtlSeconds = 604800;
    private String issuer = "trackit-auth";

    @PostConstruct
    public void validate() {
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalStateException(
                    "auth.jwt.secret must be at least 32 bytes — set AUTH_JWT_SECRET env var");
        }
    }
}
