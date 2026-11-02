/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.DocumentComment;
import ai.philterd.workspace.model.User;
import ai.philterd.workspace.repository.DocumentCommentRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.DocumentAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/v1")
public class CommentController {

    private static final int MAX_COMMENT_LENGTH = 4000;

    private final DocumentRepository documentRepository;
    private final DocumentCommentRepository commentRepository;
    private final UserRepository userRepository;
    private final DocumentAccessService documentAccessService;
    private final AuditLogService auditLogService;
    private final ai.philterd.workspace.service.BlindDoubleReviewService blindReview;

    public CommentController(final DocumentRepository documentRepository,
                             final DocumentCommentRepository commentRepository,
                             final UserRepository userRepository,
                             final DocumentAccessService documentAccessService,
                             final AuditLogService auditLogService,
                             final ai.philterd.workspace.service.BlindDoubleReviewService blindReview) {
        this.documentRepository = documentRepository;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.documentAccessService = documentAccessService;
        this.auditLogService = auditLogService;
        this.blindReview = blindReview;
    }

    public record CommentRequest(String text) {}

    @GetMapping("/documents/{id}/comments")
    public List<Map<String, Object>> list(@PathVariable final String id, final Authentication authentication) {
        final Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        // Comments can discuss the first review's decisions (#86): refused to the blind reviewer, and whoever else
        // reads them cannot then do the blind review.
        try {
            blindReview.showSpans(authentication, document);
        } catch (ai.philterd.workspace.service.BlindDoubleReviewService.Refused e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, e.getMessage());
        }

        return commentRepository.findByDocumentIdOrderByTimestampDesc(id).stream()
                .map(CommentController::toJson)
                .toList();
    }

    @PostMapping(value = "/documents/{id}/comments",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> add(@PathVariable final String id,
                                   @RequestBody final CommentRequest body,
                                   final Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Authentication is required.");
        }
        if (body == null || body.text() == null || body.text().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Comment text is required.");
        }
        final String trimmed = body.text().trim();
        if (trimmed.length() > MAX_COMMENT_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Comment is too long (max " + MAX_COMMENT_LENGTH + " characters).");
        }

        final Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);

        final String userEmail = authentication.getName();
        final String userId = userRepository.findByEmail(userEmail).map(User::getId).orElse(null);

        final DocumentComment comment = new DocumentComment();
        comment.setId(UUID.randomUUID().toString());
        comment.setDocumentId(id);
        comment.setUserEmail(userEmail);
        comment.setUserId(userId);
        comment.setText(trimmed);
        comment.setTimestamp(Instant.now());
        commentRepository.save(comment);

        auditLogService.log("DOCUMENT_COMMENT_ADD", "Document", id,
                Map.of("commentId", comment.getId(),
                        "length", trimmed.length()));

        return toJson(comment);
    }

    // ----- helpers -----

    private static Map<String, Object> toJson(final DocumentComment c) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("userEmail", c.getUserEmail() == null ? "" : c.getUserEmail());
        m.put("text", c.getText() == null ? "" : c.getText());
        m.put("timestamp", c.getTimestamp() == null ? null : c.getTimestamp().toString());
        return m;
    }

}
