package com.membership.points.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.membership.points.dto.request.EarnPointsRequest;
import com.membership.points.dto.response.PointsAccountResponse;
import com.membership.points.mapper.PointsTransactionMapper;
import com.membership.points.service.PointsAccountService;
import com.membership.points.service.PointsAdjustService;
import com.membership.points.service.PointsEarnService;
import com.membership.points.service.PointsFreezeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PointsController.class)
class PointsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PointsEarnService pointsEarnService;

    @MockBean
    private PointsAdjustService pointsAdjustService;

    @MockBean
    private PointsFreezeService pointsFreezeService;

    @MockBean
    private PointsAccountService pointsAccountService;

    @MockBean
    private PointsTransactionMapper pointsTransactionMapper;

    @Test
    void testEarnPoints_validRequest() throws Exception {
        EarnPointsRequest request = new EarnPointsRequest();
        request.setMemberId(1L);
        request.setSource("CHECKIN");
        request.setIdempotentKey("test-earn-key-001");

        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(pointsEarnService).earnPoints(any(EarnPointsRequest.class));
    }

    @Test
    void testEarnPoints_invalidRequest() throws Exception {
        // Missing memberId and source and idempotentKey - should fail validation
        EarnPointsRequest request = new EarnPointsRequest();

        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void testGetAccount() throws Exception {
        PointsAccountResponse response = new PointsAccountResponse();
        response.setMemberId(1L);
        response.setMemberNo("M00001");
        response.setMemberName("测试会员");
        response.setLevelCode("GOLD");
        response.setLevelName("黄金");
        response.setAvailablePoints(500L);
        response.setFrozenPoints(50L);
        response.setTotalEarned(1000L);
        response.setTotalSpent(450L);
        response.setTotalExpired(0L);

        when(pointsAccountService.getAccountResponse(eq(1L))).thenReturn(response);

        mockMvc.perform(get("/api/points/account/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.memberId").value(1))
                .andExpect(jsonPath("$.data.memberNo").value("M00001"))
                .andExpect(jsonPath("$.data.availablePoints").value(500))
                .andExpect(jsonPath("$.data.levelCode").value("GOLD"));
    }
}
