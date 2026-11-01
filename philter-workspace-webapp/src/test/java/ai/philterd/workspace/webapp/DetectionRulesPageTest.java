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

import ai.philterd.workspace.model.DetectionRule;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.service.DetectionRuleService;
import ai.philterd.workspace.service.GeneralSettingsService;
import ai.philterd.workspace.webapp.controllers.DetectionRulesController;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Detection Rules page (#39). */
@WebMvcTest(controllers = DetectionRulesController.class)
@Import(SecurityConfig.class)
class DetectionRulesPageTest extends WebMvcSliceMocks {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private GeneralSettingsService generalSettingsService;

    private final DetectionRule proposed = rule("r1", DetectionRule.STATUS_PROPOSED, DetectionRule.ORIGIN_REFUSALS,
            "Acme Dental");
    private final DetectionRule active = rule("r2", DetectionRule.STATUS_ACTIVE, DetectionRule.ORIGIN_REVIEWER,
            "Project Phoenix");

    private static DetectionRule rule(final String id, final String status, final String origin, final String text) {
        final DetectionRule r = new DetectionRule();
        r.setId(id);
        r.setStatus(status);
        r.setOrigin(origin);
        r.setKind(DetectionRule.STATUS_ACTIVE.equals(status) ? DetectionRule.KIND_ALWAYS : DetectionRule.KIND_NEVER);
        r.setText(text);
        r.setPiiType("person");
        r.setRefusals(5);
        r.setDocumentId("d1");
        r.setProposedBy(DetectionRule.ORIGIN_REVIEWER.equals(origin) ? "reviewer@example.com" : null);
        r.setProposedAt(Instant.parse("2026-10-30T12:00:00Z"));
        r.setDecidedBy("admin@example.com");
        r.setDecidedAt(Instant.parse("2026-10-30T13:00:00Z"));
        r.setAddedToPhilter(true);
        return r;
    }

    @BeforeEach
    void setUp() {
        final GeneralSettings settings = new GeneralSettings();
        settings.setTimezone("UTC");
        when(generalSettingsService.load()).thenReturn(settings);
        final Document document = new Document();
        document.setId("d1");
        document.setFilename("letter.pdf");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(document));
        when(detectionRuleService.visible(any())).thenReturn(List.of(proposed, active));
        when(detectionRuleService.canSee(any(), any())).thenReturn(true);
        when(detectionRuleRepository.findById("r1")).thenReturn(Optional.of(proposed));
        when(detectionRuleRepository.findById("r2")).thenReturn(Optional.of(active));
    }

    private String page(final String role) throws Exception {
        return mockMvc.perform(get("/detection-rules").with(signedIn("boss", role))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theRulesAreListedWithWhenWhoAndWhichDocument() throws Exception {
        when(detectionRuleService.mayConfirm(any(), any())).thenReturn(true);
        final String html = page("ADMIN");

        assertTrue(html.contains(">Acme Dental<") && html.contains(">Project Phoenix<"), html);
        assertTrue(html.contains("Refused 5 times in a batch") && html.contains("Made by a reviewer"));
        assertTrue(html.contains(">Philter Workspace<") && html.contains(">reviewer@example.com<"), "who proposed");
        assertTrue(html.contains("2026-10-30 12:00") && html.contains("2026-10-30 13:00"), "when");
        assertTrue(html.contains("href=\"/review/d1\"") && html.contains(">letter.pdf<"), "the source document");
        assertTrue(html.contains("action=\"/detection-rules/r1/confirm\"")
                && html.contains("action=\"/detection-rules/r1/dismiss\"")
                && html.contains("action=\"/detection-rules/r2/remove\""), html);
        assertTrue(html.contains("every PII type"), "the scope is stated");
        assertTrue(html.contains("data-confirm=\"Philter will stop flagging this value in every batch in every group, as any PII type, "
                + "ignoring case. Confirm?\""), "the whole confirmation, commas and all");
        assertTrue(html.contains("href=\"/detection-rules\""), "in the navigation");
    }

    @Test
    void someoneWhoMayNotConfirmSeesTheRulesWithoutActions() throws Exception {
        when(detectionRuleService.mayConfirm(any(), any())).thenReturn(false);
        final String html = page("AUDITOR");
        assertTrue(html.contains(">Acme Dental<") && html.contains("Waiting for confirmation"), html);
        assertFalse(html.contains("/confirm\"") || html.contains("/dismiss\"") || html.contains("/remove\""), html);
    }

    @Test
    void whenReviewersMayConfirmThePageSaysTheirRulesReachEveryGroup() throws Exception {
        final GeneralSettings settings = new GeneralSettings();
        settings.setTimezone("UTC");
        settings.setReviewersConfirmRules(true);
        when(generalSettingsService.load()).thenReturn(settings);

        final String html = page("USER");
        assertTrue(html.contains("A rule a reviewer confirms applies to every group's documents, not only their "
                + "own group's."), html);
        assertTrue(html.contains("every batch in every group"), "the scope names groups");
    }

    @Test
    void confirmingDismissingAndRemovingGoThroughTheService() throws Exception {
        mockMvc.perform(post("/detection-rules/r1/confirm").with(signedIn("boss", "ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/detection-rules")).andExpect(flash().attributeExists("success"));
        verify(detectionRuleService).confirm(eq(proposed), any());

        mockMvc.perform(post("/detection-rules/r1/dismiss").with(signedIn("boss", "ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/detection-rules"));
        verify(detectionRuleService).dismiss(eq(proposed), any());

        when(detectionRuleService.remove(eq(active), any())).thenReturn(true);
        mockMvc.perform(post("/detection-rules/r2/remove").with(signedIn("boss", "ADMIN")).with(csrf()))
                .andExpect(flash().attribute("success",
                        "Rule removed, and its value taken off Philter's list for documents redacted from now on."));
        verify(detectionRuleService).remove(eq(active), any());
    }

    @Test
    void aRefusalIsShownAndARuleThePersonCannotSeeIsNotFound() throws Exception {
        doThrow(new DetectionRuleService.RuleException("Philter said no.")).when(detectionRuleService)
                .confirm(eq(proposed), any());
        mockMvc.perform(post("/detection-rules/r1/confirm").with(signedIn("boss", "ADMIN")).with(csrf()))
                .andExpect(flash().attribute("error", "Philter said no."));

        when(detectionRuleService.canSee(any(), eq(active))).thenReturn(false);
        mockMvc.perform(post("/detection-rules/r2/remove").with(signedIn("boss", "USER")).with(csrf()))
                .andExpect(flash().attribute("error", "That rule no longer exists."));
        verify(detectionRuleService, never()).remove(any(), any());
    }
}
