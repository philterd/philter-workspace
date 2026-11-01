/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp;

import ai.philterd.workspace.model.OllamaInstance;
import ai.philterd.workspace.service.DataSourceHostAllowList;
import ai.philterd.workspace.service.LlmJudgeDefaultsService;
import ai.philterd.workspace.webapp.controllers.AdminOllamaController;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin → LLM-as-a-Judge marks an Ollama instance on another machine reached over plain HTTP (#85). */
@WebMvcTest(controllers = AdminOllamaController.class)
@Import(SecurityConfig.class)
class AdminLlmJudgePageTest extends WebMvcSliceMocks {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private LlmJudgeDefaultsService defaultsService;
    @MockitoBean private DataSourceHostAllowList hostAllowList;

    @Test
    void aPlainHttpInstanceOnAnotherMachineIsMarked() throws Exception {
        when(ollamaInstanceRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                instance("remote", "10.0.0.5"), instance("compose", "ollama"))));
        when(defaultsService.load()).thenReturn(new ai.philterd.workspace.model.LlmJudgeDefaults());

        final String html = mockMvc.perform(get("/admin/llm-judge").with(signedIn("admin", "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals(1, html.split("Plain HTTP to another machine", -1).length - 1, "only the remote instance");
        assertTrue(html.contains("put HTTPS in front of it"), "the endpoint field says so too");
    }

    private static OllamaInstance instance(final String id, final String endpoint) {
        final OllamaInstance i = new OllamaInstance();
        i.setId(id);
        i.setName(id);
        i.setEndpoint(endpoint);
        i.setPort(11434);
        return i;
    }
}
