package com.trackit.auth.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthFlowIT extends BaseIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    @Test
    void fullAuthFlow() throws Exception {
        // register
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"username":"flowuser","email":"flow@test.com","password":"password123"}
                            """))
                .andExpect(status().isCreated())
                .andReturn();

        var regBody = mapper.readTree(reg.getResponse().getContentAsString());
        String accessToken  = regBody.at("/tokens/accessToken").asText();
        String refreshToken = regBody.at("/tokens/refreshToken").asText();

        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();

        // login
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"username":"flowuser","password":"password123"}
                            """))
                .andExpect(status().isOk())
                .andReturn();

        String loginAccess  = mapper.readTree(login.getResponse().getContentAsString()).at("/tokens/accessToken").asText();
        String loginRefresh = mapper.readTree(login.getResponse().getContentAsString()).at("/tokens/refreshToken").asText();

        // validate
        MvcResult val = mockMvc.perform(post("/api/v1/auth/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + loginAccess + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(val.getResponse().getContentAsString()).get("valid").asBoolean()).isTrue();

        // /me
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + loginAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("flowuser"));

        // refresh — old refresh replaced
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + loginRefresh + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String newRefresh = mapper.readTree(refreshResult.getResponse().getContentAsString())
                .get("refreshToken").asText();

        // old refresh is now invalid
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + loginRefresh + "\"}"))
                .andExpect(status().isUnauthorized());

        // logout
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + newRefresh + "\"}"))
                .andExpect(status().isNoContent());

        // refresh after logout rejected
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + newRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
