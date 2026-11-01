/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.api.controller;

import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.service.DocumentAccessService;
import ai.philterd.workspace.service.DocumentFinalizationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/v1")
public class ExportController {

    private final DocumentFinalizationService finalizationService;
    private final SpanRepository spanRepository;
    private final DocumentRepository documentRepository;
    private final DocumentAccessService documentAccessService;

    public ExportController(final DocumentFinalizationService finalizationService,
                            final SpanRepository spanRepository,
                            final DocumentRepository documentRepository,
                            final DocumentAccessService documentAccessService) {
        this.finalizationService = finalizationService;
        this.spanRepository = spanRepository;
        this.documentRepository = documentRepository;
        this.documentAccessService = documentAccessService;
    }

    @PostMapping(value = "/documents/{id}/finalize",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> finalize(@PathVariable final String id, final Authentication authentication) {
        final Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        // The same finalization as the review page's (#61).
        try {
            return Map.of("finalizedText", finalizationService.finalizeDocument(document,
                    authentication == null ? null : authentication.getName(), "api"));
        } catch (DocumentFinalizationService.FinalizationRefused e) {
            throw new ResponseStatusException(e.status(), e.getMessage());
        }
    }

    @GetMapping("/documents/{id}/audit")
    public List<Map<String, Object>> audit(@PathVariable final String id, final Authentication authentication) {
        final Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        final List<Span> spans = spanRepository.findByDocumentId(id);
        return spans.stream().map(s -> Map.<String, Object>of(
            "text", s.getText(),
            "type", s.getType(),
            "confidence", s.getConfidence(),
            "status", s.getStatus()
        )).collect(Collectors.toList());
    }
}
