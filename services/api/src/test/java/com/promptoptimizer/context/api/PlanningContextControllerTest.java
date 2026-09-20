package com.promptoptimizer.context.api;

import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.application.PlanningSessionService;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.domain.PlanningContextPreparation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlanningContextController.class)
@Import({RequestIdFilter.class, com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration.class})
class PlanningContextControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlanningSessionService planningSessionService;

    @Test
    void shouldPrepareAContextReferenceAndSafeDigest() throws Exception {
        ContextSnapshot snapshot = new ContextSnapshot(
                "Spring Boot 用户服务",
                List.of(),
                List.of(),
                List.of("pom.xml"),
                List.of(),
                List.of(),
                List.of(),
                "test-v1"
        );
        when(planningSessionService.prepareContext(any())).thenReturn(new PlanningContextPreparation(
                "d53d3b67-62b2-4505-89dd-4ca88f837391",
                "sha256:" + "a".repeat(64),
                new PlanningContextDigest(
                        "Spring Boot 用户服务",
                        List.of("Spring Boot 3"),
                        List.of("maven:spring-boot-starter-web@3.3.13"),
                        List.of("pom.xml"),
                        List.of("pom.xml：Maven 项目配置"),
                        "COMPLETE",
                        1,
                        List.of()
                ),
                snapshot,
                Instant.parse("2026-09-14T08:30:00Z"),
                12
        ));

        mockMvc.perform(post("/api/v1/context/planning")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "rawPrompt": "给用户模块添加登录功能",
                                  "context": {
                                    "customDescription": "Spring Boot 用户服务",
                                    "files": [
                                      {"path": "pom.xml", "content": "<project />", "language": "xml"}
                                    ]
                                  },
                                  "permissionPolicy": {
                                    "protectedPaths": [],
                                    "requireConfirmationFor": []
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.data.contextId").value("d53d3b67-62b2-4505-89dd-4ca88f837391"))
                .andExpect(jsonPath("$.data.version").value("sha256:" + "a".repeat(64)))
                .andExpect(jsonPath("$.data.digest.technologies[0]").value("Spring Boot 3"))
                .andExpect(jsonPath("$.data.contextReport.directoryTree[0]").value("pom.xml"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-09-14T08:30:00Z"));
    }

    @Test
    void shouldRejectABlankPromptBeforeStartingContextAnalysis() throws Exception {
        mockMvc.perform(post("/api/v1/context/planning")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "rawPrompt": " ",
                                  "context": {"customDescription": "", "files": []}
                                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.error.message").value("请求参数校验失败。"))
                .andExpect(jsonPath("$.error.details.fields.rawPrompt").value("原始提示词不能为空"));
    }
}
