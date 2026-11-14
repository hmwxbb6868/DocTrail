package com.doctrail.controller.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JavaLivenessControllerTest {
    @Test
    void liveReturnsTheUnwrappedOperationsContract() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new JavaLivenessController()).build();

        MvcResult result = mvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("doctrail-java"))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.msg").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn();

        JsonNode payload = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        Set<String> fields = new HashSet<>();
        Iterator<String> fieldNames = payload.fieldNames();
        fieldNames.forEachRemaining(fields::add);

        assertEquals(Set.of("status", "service", "timestamp"), fields);
        String timestamp = payload.get("timestamp").asText();
        assertTrue(timestamp.endsWith("Z"));
        assertDoesNotThrow(() -> Instant.parse(timestamp));
    }
}
