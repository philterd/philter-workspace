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
import ai.philterd.workspace.model.DocumentComment;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.BlindReviewSpanRepository;
import ai.philterd.workspace.repository.DocumentCommentRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.BlindDoubleReviewService;
import ai.philterd.workspace.service.DocumentAccessService;
import ai.philterd.workspace.webapp.controllers.CommentController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Comments can discuss the first review's decisions, so the blind second reviewer is not shown them (#86). */
class CommentControllerBlindReviewTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentCommentRepository comments = mock(DocumentCommentRepository.class);
    private Document doc;
    private CommentController controller;

    @BeforeEach
    void setUp() {
        final DocumentAccessService access = TestDoubles.documentAccess(mock(BatchRepository.class), documents,
                TestDoubles.userGroups());
        final AuditLogService audit = mock(AuditLogService.class);
        controller = new CommentController(documents, comments, mock(UserRepository.class), access, audit,
                new BlindDoubleReviewService(documents, mock(SpanRepository.class),
                        mock(BlindReviewSpanRepository.class), access, audit));
        doc = new Document();
        doc.setId("d1");
        doc.setStatus("APPROVED");
        doc.setDoubleReview(true);
        doc.setFirstReviewer("first@example.com");
        doc.setSecondReviewer("second@example.com");
        when(documents.findById("d1")).thenReturn(Optional.of(doc));
        when(documents.save(any(Document.class))).thenAnswer(i -> i.getArgument(0));
        final DocumentComment comment = new DocumentComment();
        comment.setId("c1");
        comment.setText("Refused the second name: it is the judge's.");
        when(comments.findByDocumentIdOrderByTimestampDesc("d1")).thenReturn(List.of(comment));
    }

    @Test
    void theBlindReviewerIsNotShownTheComments() {
        final ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> controller.list("d1", TestAuth.admin("second@example.com")));
        assertEquals(409, refused.getStatusCode().value());
    }

    @Test
    void whoeverElseReadsThemCannotThenDoTheBlindReview() {
        assertEquals(1, controller.list("d1", TestAuth.admin("lead@example.com")).size());
        assertEquals(List.of("lead@example.com"), doc.getFirstReviewSeenBy());
    }
}
