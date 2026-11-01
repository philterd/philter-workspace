/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.model.OllamaInstance;
import ai.philterd.workspace.repository.OllamaInstanceRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.DataSourceHostAllowList;
import ai.philterd.workspace.service.LlmJudgeDefaultsService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Admin → LLM-as-a-Judge warns about an Ollama instance on another machine reached over plain HTTP (#85). */
class AdminOllamaPlainHttpTest {

    private final OllamaInstanceRepository repository = mock(OllamaInstanceRepository.class);
    private final AdminOllamaController controller = new AdminOllamaController(repository, mock(AuditLogService.class),
            mock(LlmJudgeDefaultsService.class), new DataSourceHostAllowList("10.0.0.5,ollama,localhost,127.0.0.1"));

    @Test
    void addingAPlainHttpInstanceOnAnotherMachineWarns() {
        when(repository.findByName(any())).thenReturn(Optional.empty());

        final RedirectAttributesModelMap remote = new RedirectAttributesModelMap();
        controller.create("gpu", "10.0.0.5", 11434, remote);
        assertNotNull(remote.getFlashAttributes().get("success"));
        assertEquals(AdminOllamaController.PLAIN_HTTP_WARNING, remote.getFlashAttributes().get("warning"));

        for (String endpoint : List.of("https://10.0.0.5", "ollama", "localhost")) {
            final RedirectAttributesModelMap ra = new RedirectAttributesModelMap();
            controller.create("other", endpoint, 11434, ra);
            assertNull(ra.getFlashAttributes().get("warning"), endpoint);
        }
    }

    @Test
    void theListMarksEachPlainHttpInstanceOnAnotherMachine() {
        when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                instance("remote", "10.0.0.5"), instance("secure", "https://10.0.0.5"), instance("compose", "ollama"))));

        final ExtendedModelMap model = new ExtendedModelMap();
        controller.list(model);

        assertEquals(Set.of("remote"), model.get("plainHttpInstanceIds"));
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
