package com.doctrail.controller.user;

import com.doctrail.dto.user.request.AuthRequest;
import com.doctrail.dto.user.response.AuthSessionResponse;
import com.doctrail.service.user.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerContractTest {
    @Test
    void loginReturnsStageOneResultShape() {
        AuthService service = mock(AuthService.class);
        when(service.login(new AuthRequest("demo", "password")))
                .thenReturn(new AuthSessionResponse("stage1-mock-access-token", true));

        ObjectProvider<com.doctrail.security.RefreshTokenService> refreshProvider = mock(ObjectProvider.class);
        when(refreshProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<com.doctrail.service.user.CaptchaService> captchaProvider = mock(ObjectProvider.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AuthController(service, refreshProvider, captchaProvider)).build();

        try {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"demo","password":"password"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1))
                    .andExpect(jsonPath("$.msg").value("success"))
                    .andExpect(jsonPath("$.data.accessToken").value("stage1-mock-access-token"))
                    .andExpect(jsonPath("$.data.mock").value(true));
        } catch (Exception exception) {
            throw new AssertionError("MockMvc auth contract request failed", exception);
        }
    }
}
