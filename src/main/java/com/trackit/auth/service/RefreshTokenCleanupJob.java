package com.trackit.auth.service;

import com.trackit.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${auth.cleanup.cron:0 0 3 * * *}")
    private String cron;

    @Scheduled(cron = "${auth.cleanup.cron:0 0 3 * * *}")
    @Transactional
    public void deleteExpiredTokens() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        int deleted = refreshTokenRepository.deleteExpired(cutoff);
        log.info("Refresh token cleanup: deleted {} expired tokens (cutoff={})", deleted, cutoff);
    }
}
