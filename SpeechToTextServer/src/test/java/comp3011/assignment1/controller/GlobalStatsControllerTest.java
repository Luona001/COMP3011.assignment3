package comp3011.assignment1.controller;

import comp3011.assignment1.service.ServerStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.when;

@WebMvcTest(GlobalStatsController.class)
class GlobalStatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServerStatsService statsService;

    @Test
    void stats_returnsTokenCounts() throws Exception {
        mockMvc.perform(get("/api/v1/global/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputTokens").isNumber())
                .andExpect(jsonPath("$.outputTokens").isNumber());
    }
    
    @Test
    void usage_returnsCumulativeSeconds() throws Exception {
        when(statsService.getUsageSeconds()).thenReturn(12.5);

        mockMvc.perform(get("/api/v1/global/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage").value(12.5));
    }
}