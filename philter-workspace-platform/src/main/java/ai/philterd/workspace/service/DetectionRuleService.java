/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.service;

import ai.philterd.philter.model.RedactList;
import ai.philterd.philter.model.RedactListTerms;
import ai.philterd.philter.model.exceptions.ErrorReasons;
import ai.philterd.philter.model.exceptions.PhilterError;
import ai.philterd.workspace.model.DetectionRule;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.repository.DetectionRuleRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Turns reviewers' decisions into rules Philter applies to later documents (#39). A rule is a term on the
 * never-redact or always-redact list of the Philter user whose key ({@code PHILTER_WORKSPACE_PHILTER_API_KEY})
 * redacts batches. Philter applies those lists to every policy of that user, to every PII type, and without
 * regard to case, so a rule applies to every batch.
 *
 * <p>A rule is proposed first and written to Philter only once someone allowed to confirm rules confirms it:
 * an administrator, or also a reviewer with access to the rule's document when
 * {@link GeneralSettings#isReviewersConfirmRules()} is on. A rule made by someone allowed to confirm it is
 * confirmed as it is made.
 */
@Service
public class DetectionRuleService {

    private static final Logger log = LoggerFactory.getLogger(DetectionRuleService.class);

    /** Philter's longest term on a redact list. */
    public static final int MAX_TERM_LENGTH = 100;
    /** Philter reads a term ending in this as a fuzzy match. */
    private static final String FUZZY_SUFFIX = ":fuzzy";
    private static final int WRITE_ATTEMPTS = 3;

    static final String KEY_REJECTED = "Philter rejected PHILTER_WORKSPACE_PHILTER_API_KEY, so the rule was not "
            + "written to Philter.";
    static final String KEY_SCOPES = "PHILTER_WORKSPACE_PHILTER_API_KEY needs the lists:read and lists:write scopes "
            + "for rules to be written to Philter. Nothing was changed.";
    static final String LIST_REFUSED = "Philter refused the change. Its never-redact and always-redact lists each "
            + "hold up to 1,000 terms of up to 100 characters. Nothing was changed.";
    static final String UNREACHABLE = "Philter could not be reached, or did not answer as expected. Nothing was "
            + "changed. Try again.";
    static final String KEPT_CHANGING = "Philter's list changed while the rule was being written. Nothing was "
            + "changed. Try again.";

    private final DetectionRuleRepository rules;
    private final SpanRepository spans;
    private final DocumentRepository documents;
    private final DocumentAccessService documentAccess;
    private final PhilterSettings philter;
    private final GeneralSettingsService settings;
    private final AuditLogService audit;

    public DetectionRuleService(final DetectionRuleRepository rules, final SpanRepository spans,
                                final DocumentRepository documents, final DocumentAccessService documentAccess,
                                final PhilterSettings philter, final GeneralSettingsService settings,
                                final AuditLogService audit) {
        this.rules = rules;
        this.spans = spans;
        this.documents = documents;
        this.documentAccess = documentAccess;
        this.philter = philter;
        this.settings = settings;
        this.audit = audit;
    }

    /** A refused request, with Philter Workspace's own message for the person who made it. */
    public static class RuleException extends RuntimeException {
        public RuleException(final String message) {
            super(message);
        }
    }

    /** What making a rule did: the rule, and whether it is now in Philter or waits for confirmation. */
    public record Made(DetectionRule rule, String message) {
    }

    /** A value's key: Philter compares a list's terms with text trimmed and without regard to case. */
    public String textKey(final String text) {
        return audit.hashForAudit(normalize(text));
    }

    static String normalize(final String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Makes a rule from a span: {@link DetectionRule#KIND_NEVER} from a refused detected span,
     * {@link DetectionRule#KIND_ALWAYS} from a span a reviewer added. The caller has checked the person may see
     * the span's document.
     */
    public Made makeFromSpan(final String kind, final Span span, final Document document, final Authentication auth) {
        if (DetectionRule.KIND_NEVER.equals(kind)) {
            if (span.isManuallyCreated() || !"REJECTED".equals(span.getStatus())) {
                throw new RuleException("Only a refused span can be made a \"Don't flag this again\" rule.");
            }
        } else if (DetectionRule.KIND_ALWAYS.equals(kind)) {
            if (!span.isManuallyCreated()) {
                throw new RuleException("Only a span added by hand can be made an \"Always flag this\" rule.");
            }
        } else {
            throw new RuleException("Unknown kind of rule.");
        }
        final String text = checkedText(span.getText());
        final String key = textKey(text);

        DetectionRule rule = null;
        for (DetectionRule existing : rules.findByTextKey(key)) {
            if (existing.getKind().equals(kind)) {
                rule = existing;
            } else if (!DetectionRule.STATUS_DISMISSED.equals(existing.getStatus())) {
                throw new RuleException(existing.isActive()
                        ? "A rule already says to " + verb(existing.getKind()) + " this value. Remove it on the "
                                + "Rules page first."
                        : "A rule to " + verb(existing.getKind()) + " this value is waiting to be confirmed. "
                                + "Dismiss it on the Detection Rules page first.");
            }
        }
        if (rule != null && rule.isActive()) {
            return new Made(rule, "A rule already says to " + verb(kind) + " this value.");
        }
        final boolean created = rule == null;
        if (created) {
            rule = new DetectionRule();
            rule.setId(UUID.randomUUID().toString());
            rule.setKind(kind);
            rule.setTextKey(key);
        }
        if (created || DetectionRule.STATUS_DISMISSED.equals(rule.getStatus())) {
            rule.setStatus(DetectionRule.STATUS_PROPOSED);
            rule.setOrigin(DetectionRule.ORIGIN_REVIEWER);
            rule.setText(text);
            rule.setPiiType(span.getType());
            rule.setDocumentId(document.getId());
            rule.setBatchId(document.getBatchId());
            rule.setRefusals(0);
            rule.setProposedBy(auth == null ? null : auth.getName());
            rule.setProposedAt(Instant.now());
            rule.setDecidedBy(null);
            rule.setDecidedAt(null);
            rule.setAddedToPhilter(false);
            if (mayConfirm(auth, rule)) {
                // Written to Philter before it is saved, so a refused write leaves nothing behind.
                final boolean added = addTerm(list(rule), text);
                audit.log("DETECTION_RULE_CREATE", "DetectionRule", rule.getId(), details(rule));
                activate(rule, added, auth);
                return new Made(rule, confirmed(kind));
            }
            rule = rules.save(rule);
            audit.log("DETECTION_RULE_CREATE", "DetectionRule", rule.getId(), details(rule));
        } else if (mayConfirm(auth, rule)) {
            confirm(rule, auth);
            return new Made(rule, confirmed(kind));
        } else {
            return new Made(rule, "A rule to " + verb(kind) + " this value is already waiting to be confirmed on the "
                    + "Detection Rules page.");
        }
        return new Made(rule, "Rule proposed. It applies once someone allowed to confirm rules confirms it on "
                + "the Detection Rules page.");
    }

    private static String confirmed(final String kind) {
        return "Rule confirmed: Philter will " + verb(kind) + " this value in documents redacted from now on.";
    }

    /** Writes a proposed rule to Philter and makes it active. */
    public void confirm(final DetectionRule rule, final Authentication auth) {
        requireMayConfirm(auth, rule);
        if (!rule.isProposed()) {
            throw new RuleException("Only a proposed rule can be confirmed.");
        }
        for (DetectionRule other : rules.findByTextKey(rule.getTextKey())) {
            if (other.isActive() && !other.getKind().equals(rule.getKind())) {
                throw new RuleException("A rule already says to " + verb(other.getKind()) + " this value. Remove it "
                        + "first.");
            }
        }
        activate(rule, addTerm(list(rule), checkedText(rule.getText())), auth);
    }

    private void activate(final DetectionRule rule, final boolean added, final Authentication auth) {
        rule.setStatus(DetectionRule.STATUS_ACTIVE);
        rule.setAddedToPhilter(added);
        rule.setDecidedBy(auth == null ? null : auth.getName());
        rule.setDecidedAt(Instant.now());
        rules.save(rule);
        final Map<String, Object> details = details(rule);
        details.put("addedToPhilter", added);
        audit.log("DETECTION_RULE_CONFIRM", "DetectionRule", rule.getId(), details);
    }

    /** Dismisses a proposed rule. It is kept, so the same value is not proposed again after more refusals. */
    public void dismiss(final DetectionRule rule, final Authentication auth) {
        requireMayConfirm(auth, rule);
        if (!rule.isProposed()) {
            throw new RuleException("Only a proposed rule can be dismissed.");
        }
        rule.setStatus(DetectionRule.STATUS_DISMISSED);
        rule.setDecidedBy(auth == null ? null : auth.getName());
        rule.setDecidedAt(Instant.now());
        rules.save(rule);
        audit.log("DETECTION_RULE_DISMISS", "DetectionRule", rule.getId(), details(rule));
    }

    /**
     * Removes a rule. An active rule's term is taken off Philter's list first, unless the term was already there
     * when the rule was confirmed. Returns whether a term was taken off Philter's list.
     */
    public boolean remove(final DetectionRule rule, final Authentication auth) {
        requireMayConfirm(auth, rule);
        final Map<String, Object> details = details(rule);
        boolean removed = false;
        if (rule.isActive() && rule.isAddedToPhilter()) {
            removed = removeTerm(list(rule), rule.getText());
            details.put("removedFromPhilter", removed);
        }
        rules.deleteById(rule.getId());
        audit.log("DETECTION_RULE_REMOVE", "DetectionRule", rule.getId(), details);
        return removed;
    }

    /**
     * Called after a reviewer refuses a detected span. Proposes never flagging the value once it has been
     * refused {@link GeneralSettings#getRuleProposalRefusals()} times in the batch's documents and accepted in
     * none of them, unless a rule for it, in any state, already exists. Best effort: a failure is logged and
     * leaves the refusal as it is.
     */
    public void noteRefusal(final Span span, final Document document) {
        try {
            final int threshold = settings.load().getRuleProposalRefusals();
            if (threshold <= 0 || span.isManuallyCreated() || document.getBatchId() == null) {
                return;
            }
            final String normalized = normalize(span.getText());
            if (normalized.isEmpty() || normalized.length() > MAX_TERM_LENGTH || normalized.endsWith(FUZZY_SUFFIX)
                    || PiiFieldEncryption.UNDECRYPTABLE.equals(span.getText())) {
                return;
            }
            final String key = audit.hashForAudit(normalized);
            if (!rules.findByTextKey(key).isEmpty()) {
                return;
            }
            final List<String> documentIds = documents.findByBatchId(document.getBatchId()).stream()
                    .map(Document::getId).toList();
            int refusals = 0;
            for (Span s : spans.findByDocumentIdInAndStatusIn(documentIds, List.of("REJECTED", "APPROVED"))) {
                if (!normalized.equals(normalize(s.getText()))) {
                    continue;
                }
                if ("APPROVED".equals(s.getStatus())) {
                    return;
                }
                refusals++;
            }
            if (refusals < threshold) {
                return;
            }
            final DetectionRule rule = new DetectionRule();
            rule.setId(UUID.randomUUID().toString());
            rule.setKind(DetectionRule.KIND_NEVER);
            rule.setStatus(DetectionRule.STATUS_PROPOSED);
            rule.setOrigin(DetectionRule.ORIGIN_REFUSALS);
            rule.setText(span.getText().trim());
            rule.setTextKey(key);
            rule.setPiiType(span.getType());
            rule.setDocumentId(document.getId());
            rule.setBatchId(document.getBatchId());
            rule.setRefusals(refusals);
            rule.setProposedAt(Instant.now());
            rules.save(rule);
            final Map<String, Object> details = details(rule);
            details.put("refusals", refusals);
            audit.log("DETECTION_RULE_PROPOSE", "DetectionRule", rule.getId(), details);
        } catch (RuntimeException e) {
            log.warn("Could not check whether to propose a detection rule for span {}: {}", span.getId(),
                    e.getClass().getSimpleName());
        }
    }

    /** The rules this person may see: every rule for an administrator or auditor, else those of documents they can see. */
    public List<DetectionRule> visible(final Authentication auth) {
        final List<DetectionRule> visible = new ArrayList<>();
        for (DetectionRule rule : rules.findAll()) {
            if (canSee(auth, rule)) {
                visible.add(rule);
            }
        }
        visible.sort(Comparator.comparing(DetectionRule::getProposedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return visible;
    }

    /** Whether this person may see the rule: an administrator or auditor, or someone who can see its document. */
    public boolean canSee(final Authentication auth, final DetectionRule rule) {
        return AuthUtils.isAdminOrAuditor(auth) || canSeeDocument(auth, rule);
    }

    /** Whether this person may confirm, dismiss, or remove the rule. */
    public boolean mayConfirm(final Authentication auth, final DetectionRule rule) {
        if (AuthUtils.isAdmin(auth)) {
            return true;
        }
        if (auth == null || AuthUtils.isAuditor(auth) || !settings.load().isReviewersConfirmRules()) {
            return false;
        }
        return canSeeDocument(auth, rule);
    }

    private void requireMayConfirm(final Authentication auth, final DetectionRule rule) {
        if (!mayConfirm(auth, rule)) {
            throw new RuleException("You are not allowed to confirm, dismiss, or remove rules.");
        }
    }

    private boolean canSeeDocument(final Authentication auth, final DetectionRule rule) {
        if (rule.getDocumentId() == null) {
            return false;
        }
        final Document document = documents.findById(rule.getDocumentId()).orElse(null);
        if (document == null) {
            return false;
        }
        try {
            documentAccess.requireDocumentAccess(auth, document);
            return true;
        } catch (ResponseStatusException e) {
            return false;
        }
    }

    private static String checkedText(final String raw) {
        final String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new RuleException("The span has no text to make a rule from.");
        }
        // Checked before Philter's list is written, since saving the rule would then be refused (#74).
        if (PiiFieldEncryption.UNDECRYPTABLE.equals(text)) {
            throw new RuleException("The span's value could not be decrypted, so no rule can be made from it.");
        }
        if (text.length() > MAX_TERM_LENGTH) {
            throw new RuleException("Philter's lists take values of up to " + MAX_TERM_LENGTH + " characters, and "
                    + "this one has " + text.length() + ".");
        }
        if (text.toLowerCase(Locale.ROOT).endsWith(FUZZY_SUFFIX)) {
            throw new RuleException("A value ending in \"" + FUZZY_SUFFIX + "\" cannot be made a rule, since Philter "
                    + "reads that ending as a fuzzy match.");
        }
        return text;
    }

    private static RedactList list(final DetectionRule rule) {
        return DetectionRule.KIND_ALWAYS.equals(rule.getKind()) ? RedactList.ALWAYS : RedactList.NEVER;
    }

    private static String verb(final String kind) {
        return DetectionRule.KIND_ALWAYS.equals(kind) ? "always flag" : "never flag";
    }

    private Map<String, Object> details(final DetectionRule rule) {
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", rule.getKind());
        details.put("status", rule.getStatus());
        details.put("origin", rule.getOrigin());
        details.put("textHash", rule.getTextKey());
        details.put("textLength", rule.getText() == null ? 0 : rule.getText().trim().length());
        details.put("piiType", rule.getPiiType() == null ? "" : rule.getPiiType());
        details.put("documentId", rule.getDocumentId() == null ? "" : rule.getDocumentId());
        details.put("batchId", rule.getBatchId() == null ? "" : rule.getBatchId());
        return details;
    }

    /** Adds the term to the list unless a term equal to it, ignoring case, is there. Returns whether it added it. */
    private boolean addTerm(final RedactList list, final String term) {
        return update(list, terms -> {
            if (terms.stream().anyMatch(t -> t.trim().equalsIgnoreCase(term))) {
                return false;
            }
            terms.add(term);
            return true;
        });
    }

    /**
     * Removes the term this rule added, as written. A variant in another case was put there by someone else since,
     * so it is left, though Philter matches it the same.
     */
    private boolean removeTerm(final RedactList list, final String term) {
        final String trimmed = term == null ? "" : term.trim();
        return update(list, terms -> terms.removeIf(t -> t.trim().equals(trimmed)));
    }

    private interface Change {
        /** Changes {@code terms} in place; returns whether it changed them. */
        boolean apply(List<String> terms);
    }

    /** Reads the list, changes it, and writes it back only if it is still at the revision read. */
    private boolean update(final RedactList list, final Change change) {
        try {
            for (int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
                final RedactListTerms current = philter.client().getRedactList(list);
                final List<String> terms = new ArrayList<>(current.getTerms() == null ? List.of() : current.getTerms());
                if (!change.apply(terms)) {
                    return false;
                }
                try {
                    philter.client().replaceRedactList(list, terms, current.getRevision());
                    return true;
                } catch (RuntimeException e) {
                    if (!(e instanceof PhilterError error) || !ErrorReasons.REDACT_LIST_CHANGED.equals(error.getReason())) {
                        throw e;
                    }
                }
            }
            throw new RuleException(KEPT_CHANGING);
        } catch (RuleException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not update Philter's {} redact list: {}", list.getName(), PhilterClients.describe(e));
            throw new RuleException(message(e));
        }
    }

    /** Philter Workspace's own message for a refused call, never Philter's text. */
    static String message(final Exception e) {
        if (e instanceof PhilterError error) {
            switch (error.getStatusCode()) {
                case 401:
                    return KEY_REJECTED;
                case 403:
                    return KEY_SCOPES;
                case 400:
                    return LIST_REFUSED;
                default:
                    break;
            }
        }
        return UNREACHABLE;
    }
}
