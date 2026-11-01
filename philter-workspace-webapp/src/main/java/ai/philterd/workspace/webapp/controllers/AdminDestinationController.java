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

import ai.philterd.workspace.model.LocalDirectoryDestination;
import ai.philterd.workspace.model.S3Destination;
import ai.philterd.workspace.repository.LocalDirectoryDestinationRepository;
import ai.philterd.workspace.repository.S3DestinationRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.DestinationTester;
import ai.philterd.workspace.service.SymmetricCipher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD for redaction destinations — places finalized documents are written to.
 * Today the page supports two destination types: local filesystem directories
 * and Amazon S3 buckets. Mirrors the layout of {@link AdminDataSourceController}.
 *
 * <p>A batch delivers its finalized documents to one destination (#24; see {@code DocumentDeliveryService}), so a
 * destination a batch uses cannot be removed.
 */
@Controller
@RequestMapping("/admin/destinations")
public class AdminDestinationController {

    private final LocalDirectoryDestinationRepository localRepository;
    private final S3DestinationRepository s3Repository;
    private final AuditLogService auditLogService;
    private final SymmetricCipher cipher;
    private final DestinationTester destinationTester;
    private final ai.philterd.workspace.repository.BatchRepository batchRepository;

    private final ai.philterd.workspace.service.LocalDirectoryRoots localDirectoryRoots;

    public AdminDestinationController(final LocalDirectoryDestinationRepository localRepository,
                                      final S3DestinationRepository s3Repository,
                                      final AuditLogService auditLogService,
                                      final SymmetricCipher cipher,
                                      final DestinationTester destinationTester,
                                      final ai.philterd.workspace.repository.BatchRepository batchRepository,
                                      final ai.philterd.workspace.service.LocalDirectoryRoots localDirectoryRoots) {
        this.localDirectoryRoots = localDirectoryRoots;
        this.localRepository = localRepository;
        this.s3Repository = s3Repository;
        this.auditLogService = auditLogService;
        this.cipher = cipher;
        this.destinationTester = destinationTester;
        this.batchRepository = batchRepository;
    }

    @GetMapping
    public String list(final Model model) {
        final List<LocalDirectoryDestination> localDestinations = localRepository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        model.addAttribute("localDestinations", localDestinations);

        final List<S3Destination> s3Destinations = s3Repository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        model.addAttribute("s3Destinations", s3Destinations);

        return "admin-destinations";
    }

    // ------------------------------------------------------------------
    // Local Directory
    // ------------------------------------------------------------------

    @PostMapping("/local")
    public String createLocal(@RequestParam("name") final String name,
                              @RequestParam("directoryPath") final String directoryPath,
                              final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedPath = directoryPath == null ? "" : directoryPath.trim();

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/destinations";
        }
        if (trimmedPath.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Directory path is required.");
            return "redirect:/admin/destinations";
        }
        // #72: only under an administrator-configured root, by its real path.
        if (localDirectoryRoots.confine(trimmedPath) == null) {
            redirectAttributes.addFlashAttribute("error", ai.philterd.workspace.service.LocalDirectoryRoots.NOT_ALLOWED);
            return "redirect:/admin/destinations";
        }
        if (localRepository.findFirstByNameIgnoreCase(trimmedName).isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A local directory destination named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/destinations";
        }

        final LocalDirectoryDestination dest = new LocalDirectoryDestination();
        dest.setId(UUID.randomUUID().toString());
        dest.setName(trimmedName);
        dest.setDirectoryPath(trimmedPath);
        dest.setCreatedAt(LocalDateTime.now());

        try {
            localRepository.save(dest);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "A local directory destination named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/destinations";
        }

        auditLogService.log("LOCAL_DESTINATION_CREATE", "LocalDirectoryDestination", dest.getId(),
                Map.of("name", trimmedName, "directoryPath", trimmedPath));
        redirectAttributes.addFlashAttribute("success",
                "Local directory destination \"" + trimmedName + "\" added.");
        return "redirect:/admin/destinations";
    }

    @PostMapping("/local/{id}/edit")
    public String editLocal(@PathVariable final String id,
                            @RequestParam("directoryPath") final String directoryPath,
                            final RedirectAttributes redirectAttributes) {
        final LocalDirectoryDestination dest = localRepository.findById(id).orElse(null);
        if (dest == null) {
            redirectAttributes.addFlashAttribute("error", "Local directory destination not found.");
            return "redirect:/admin/destinations";
        }
        final String trimmedPath = directoryPath == null ? "" : directoryPath.trim();
        if (trimmedPath.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Directory path is required.");
            return "redirect:/admin/destinations";
        }
        // #72: only under an administrator-configured root, by its real path.
        if (localDirectoryRoots.confine(trimmedPath) == null) {
            redirectAttributes.addFlashAttribute("error", ai.philterd.workspace.service.LocalDirectoryRoots.NOT_ALLOWED);
            return "redirect:/admin/destinations";
        }

        dest.setDirectoryPath(trimmedPath);
        localRepository.save(dest);

        auditLogService.log("LOCAL_DESTINATION_UPDATE", "LocalDirectoryDestination", id,
                Map.of("name", dest.getName() == null ? "" : dest.getName(),
                        "directoryPath", trimmedPath));
        redirectAttributes.addFlashAttribute("success",
                "Local directory destination \"" + dest.getName() + "\" updated.");
        return "redirect:/admin/destinations";
    }

    @PostMapping("/local/{id}/delete")
    public String deleteLocal(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final LocalDirectoryDestination dest = localRepository.findById(id).orElse(null);
        if (dest == null) {
            redirectAttributes.addFlashAttribute("error", "Local directory destination not found.");
            return "redirect:/admin/destinations";
        }
        // A batch delivering to it (#24) would otherwise fail every delivery.
        if (batchRepository.existsByDestinationTypeAndDestinationId("LOCAL", id)) {
            redirectAttributes.addFlashAttribute("error", "Local directory destination \"" + dest.getName()
                    + "\" is used by a batch. Choose another destination for that batch first.");
            return "redirect:/admin/destinations";
        }
        localRepository.deleteById(id);
        auditLogService.log("LOCAL_DESTINATION_DELETE", "LocalDirectoryDestination", id,
                Map.of("name", dest.getName() == null ? "" : dest.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Local directory destination \"" + dest.getName() + "\" removed.");
        return "redirect:/admin/destinations";
    }

    // ------------------------------------------------------------------
    // Amazon S3
    // ------------------------------------------------------------------

    @PostMapping("/s3")
    public String createS3(@RequestParam("name") final String name,
                           @RequestParam("bucketName") final String bucketName,
                           @RequestParam("bucketKey") final String bucketKey,
                           @RequestParam(value = "accessKey", required = false) final String accessKey,
                           @RequestParam(value = "secretKey", required = false) final String secretKey,
                           final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedBucket = bucketName == null ? "" : bucketName.trim();
        final String trimmedKey = bucketKey == null ? "" : bucketKey.trim();
        // Credentials are not trimmed — AWS keys can theoretically contain whitespace.
        final String rawAccessKey = accessKey == null ? "" : accessKey;
        final String rawSecretKey = secretKey == null ? "" : secretKey;

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/destinations";
        }
        if (trimmedBucket.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Bucket name is required.");
            return "redirect:/admin/destinations";
        }
        if (trimmedKey.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Bucket key is required.");
            return "redirect:/admin/destinations";
        }
        // An access key without a secret key (or vice versa) is almost certainly a mistake.
        if (rawAccessKey.isEmpty() != rawSecretKey.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "Provide both Access key and Secret key, or leave both blank.");
            return "redirect:/admin/destinations";
        }
        if (s3Repository.findFirstByNameIgnoreCase(trimmedName).isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "An S3 destination named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/destinations";
        }

        final S3Destination dest = new S3Destination();
        dest.setId(UUID.randomUUID().toString());
        dest.setName(trimmedName);
        dest.setBucketName(trimmedBucket);
        dest.setBucketKey(trimmedKey);
        dest.setEncryptedAccessKey(rawAccessKey.isEmpty() ? null : cipher.encrypt(rawAccessKey));
        dest.setEncryptedSecretKey(rawSecretKey.isEmpty() ? null : cipher.encrypt(rawSecretKey));
        dest.setCreatedAt(LocalDateTime.now());

        try {
            s3Repository.save(dest);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "An S3 destination named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/destinations";
        }

        auditLogService.log("S3_DESTINATION_CREATE", "S3Destination", dest.getId(),
                Map.of("name", trimmedName,
                        "bucketName", trimmedBucket,
                        "bucketKey", trimmedKey,
                        "credentialsSet", dest.getEncryptedAccessKey() != null));
        redirectAttributes.addFlashAttribute("success",
                "S3 destination \"" + trimmedName + "\" added.");
        return "redirect:/admin/destinations";
    }

    @PostMapping("/s3/{id}/edit")
    public String editS3(@PathVariable final String id,
                         @RequestParam("bucketName") final String bucketName,
                         @RequestParam("bucketKey") final String bucketKey,
                         @RequestParam(value = "accessKey", required = false) final String accessKey,
                         @RequestParam(value = "secretKey", required = false) final String secretKey,
                         @RequestParam(value = "clearCredentials", required = false) final String clearCredentials,
                         final RedirectAttributes redirectAttributes) {
        final S3Destination dest = s3Repository.findById(id).orElse(null);
        if (dest == null) {
            redirectAttributes.addFlashAttribute("error", "S3 destination not found.");
            return "redirect:/admin/destinations";
        }

        final String trimmedBucket = bucketName == null ? "" : bucketName.trim();
        final String trimmedKey = bucketKey == null ? "" : bucketKey.trim();
        final String rawAccessKey = accessKey == null ? "" : accessKey;
        final String rawSecretKey = secretKey == null ? "" : secretKey;
        final boolean clear = "true".equalsIgnoreCase(clearCredentials);

        if (trimmedBucket.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Bucket name is required.");
            return "redirect:/admin/destinations";
        }
        if (trimmedKey.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Bucket key is required.");
            return "redirect:/admin/destinations";
        }
        // Without "clear", supplying just one half of the pair is almost certainly a mistake.
        if (!clear && rawAccessKey.isEmpty() != rawSecretKey.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "Provide both Access key and Secret key, leave both blank to keep existing, or check Clear credentials.");
            return "redirect:/admin/destinations";
        }

        dest.setBucketName(trimmedBucket);
        dest.setBucketKey(trimmedKey);

        final boolean credentialsChanged;
        if (clear) {
            dest.setEncryptedAccessKey(null);
            dest.setEncryptedSecretKey(null);
            credentialsChanged = true;
        } else if (!rawAccessKey.isEmpty()) {
            dest.setEncryptedAccessKey(cipher.encrypt(rawAccessKey));
            dest.setEncryptedSecretKey(cipher.encrypt(rawSecretKey));
            credentialsChanged = true;
        } else {
            // Both blank, no clear: keep existing encrypted credentials untouched.
            credentialsChanged = false;
        }

        s3Repository.save(dest);

        auditLogService.log("S3_DESTINATION_UPDATE", "S3Destination", id,
                Map.of("name", dest.getName() == null ? "" : dest.getName(),
                        "bucketName", trimmedBucket,
                        "bucketKey", trimmedKey,
                        "credentialsChanged", credentialsChanged,
                        "credentialsCleared", clear));
        redirectAttributes.addFlashAttribute("success",
                "S3 destination \"" + dest.getName() + "\" updated.");
        return "redirect:/admin/destinations";
    }

    @PostMapping("/s3/{id}/delete")
    public String deleteS3(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final S3Destination dest = s3Repository.findById(id).orElse(null);
        if (dest == null) {
            redirectAttributes.addFlashAttribute("error", "S3 destination not found.");
            return "redirect:/admin/destinations";
        }
        // A batch delivering to it (#24) would otherwise fail every delivery.
        if (batchRepository.existsByDestinationTypeAndDestinationId("S3", id)) {
            redirectAttributes.addFlashAttribute("error", "S3 destination \"" + dest.getName()
                    + "\" is used by a batch. Choose another destination for that batch first.");
            return "redirect:/admin/destinations";
        }
        s3Repository.deleteById(id);
        auditLogService.log("S3_DESTINATION_DELETE", "S3Destination", id,
                Map.of("name", dest.getName() == null ? "" : dest.getName()));
        redirectAttributes.addFlashAttribute("success",
                "S3 destination \"" + dest.getName() + "\" removed.");
        return "redirect:/admin/destinations";
    }

    // ------------------------------------------------------------------
    // Test endpoints — exercised by the Test buttons on the Add forms.
    // Each returns JSON: { ok: boolean, message?: string, error?: string }.
    // No persistence; no audit log entry — these are read-only probes from
    // the operator's perspective (though they do write a small object/file
    // to the destination, which the operator explicitly asked to happen).
    // ------------------------------------------------------------------

    @PostMapping(value = "/local/test", produces = "application/json")
    public ResponseEntity<Map<String, Object>> testLocal(
            @RequestParam("directoryPath") final String directoryPath) {
        return toJson(destinationTester.testLocalDirectory(directoryPath));
    }

    @PostMapping(value = "/s3/test", produces = "application/json")
    public ResponseEntity<Map<String, Object>> testS3(
            @RequestParam("bucketName") final String bucketName,
            @RequestParam("bucketKey") final String bucketKey,
            @RequestParam(value = "accessKey", required = false) final String accessKey,
            @RequestParam(value = "secretKey", required = false) final String secretKey) {
        return toJson(destinationTester.testS3(bucketName, bucketKey, accessKey, secretKey));
    }

    private static ResponseEntity<Map<String, Object>> toJson(final DestinationTester.TestResult result) {
        final Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("ok", result.isOk());
        if (result.getMessage() != null) body.put("message", result.getMessage());
        if (result.getError() != null) body.put("error", result.getError());
        return ResponseEntity.ok(body);
    }
}
