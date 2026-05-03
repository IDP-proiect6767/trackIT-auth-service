package com.trackit.auth.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityIT extends BaseIT {

    @Autowired MockMvc mockMvc;

    @Test
    void protectedRoute_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void protectedRoute_garbageToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer notavalidtoken"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedRoute_wrongSignatureKey_returns401() throws Exception {
        // JWT signed with a different key — crafted manually with header.payload.wrongsig
        String fakeToken = "eyJhbGciOiJIUzI1NiJ9" +
                ".eyJzdWIiOiIxIiwidXNlcm5hbWUiOiJoYWNrZXIiLCJyb2xlIjoiQURNSU4ifQ" +
                ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + fakeToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validate_invalidToken_returns200WithValidFalse() throws Exception {
        mockMvc.perform(post("/api/v1/auth/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"garbage\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }
}
