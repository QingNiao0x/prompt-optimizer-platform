package com.promptoptimizer.context.dto;

import com.promptoptimizer.context.controller.ContextAnalysisController;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.context.service.ContextAnalyzer;
import com.promptoptimizer.context.domain.ContextSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@WebMvcTest(ContextAnalysisController.class)
@Import({RequestIdFilter.class, com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration.class})
class ContextAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ContextAnalyzer contextAnalyzer;

    @MockBean
    private AnalyticsEventService analyticsEventService;

    @Test
    void shouldReturnContextSnapshot() throws Exception {
        when(contextAnalyzer.analyze(any())).thenReturn(new ContextSnapshot(
                "项目描述",
                List.of(),
                List.of(),
                List.of("backend/", "backend/pom.xml"),
                List.of(),
                List.of(),
                List.of(),
                "v1"
        ));

        mockMvc.perform(post("/api/v1/context/analyze")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "customDescription": "项目描述",
                                  "files": [
                                    {"path": "backend/pom.xml", "content": "<project />", "language": "xml"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.data.customDescription").value("项目描述"))
                .andExpect(jsonPath("$.data.directoryTree[0]").value("backend/"));
    }

    @Test
    void shouldRejectBlankFilePath() throws Exception {
        mockMvc.perform(post("/api/v1/context/analyze")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "files": [
                                    {"path": "", "content": "class App {}"}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }
}
