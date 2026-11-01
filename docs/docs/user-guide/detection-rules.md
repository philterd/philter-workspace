# Detection rules

A detection rule tells Philter to stop flagging a value, or to always flag one, in
documents redacted after the rule is confirmed. Rules come from reviewers' decisions, so a
value refused on every document, such as an office's own name on its letterhead, need not
be refused again, and a value a reviewer keeps adding by hand is found without them.

Rules change what Philter detects in later documents. They do not change documents
already redacted, and detection stays probabilistic: validate the output against your own
data.

## Kinds of rule

| Rule | Made from | Philter list | Effect |
|------|-----------|--------------|--------|
| **Don't flag this again** | A refused detected span | Never redact | Philter leaves the value alone. |
| **Always flag this** | A span added by hand | Always redact | Philter flags the value, reported as PII type `custom-dictionary`. |

## Scope

A confirmed rule is a term on the never-redact or always-redact list of the Philter user
who owns `PHILTER_WORKSPACE_PHILTER_API_KEY`, the key Philter Workspace redacts batches
with. Philter applies those lists to every redaction that user makes, so a rule:

- applies to **every batch**, in every group, whatever its policy;
- applies to **every PII type**: ignoring "Smith" refused as an organization also stops it
  being flagged as a person's name;
- matches **without regard to case**, after trimming the value.

The value is matched as written, not as a pattern. Philter's lists hold up to 1,000 terms
of up to 100 characters each; a longer value, or one ending in `:fuzzy` (which Philter
reads as a fuzzy match), cannot be made a rule.

The **Always and Never Redact** tab of the Policies page shows the lists of the person
signed in, which are these lists only for the user who owns
`PHILTER_WORKSPACE_PHILTER_API_KEY`. An administrator sees them there with the **Owner**
filter.

## Requirements

`PHILTER_WORKSPACE_PHILTER_API_KEY` needs the `lists:read` and `lists:write` scopes, in
addition to `redact` and `policies:read`. Without them Philter refuses the change,
Philter Workspace says so, and no rule is made. See
[Philter API key for Philter Workspace](../workflow.md#philter-api-key-for-philter-workspace).

## Making a rule

On the review page, a refused detected span has a **Don't flag this again** button, and a
span added by hand has **Always flag this**. Each states the rule's scope and asks for
confirmation. The buttons are available whatever the document's status, since a rule
changes later documents rather than this one.

- If you may confirm rules, the rule is written to Philter at once.
- Otherwise it is **proposed**, and applies once someone allowed to confirm rules confirms
  it on the **Detection Rules** page.

A value cannot have both kinds of rule. Remove or dismiss the other one first.

## Proposals from repeated refusals

When a reviewer refuses a detected span, Philter Workspace counts the spans with the same
value, ignoring case, in the batch's documents. If the value has been refused the
configured number of times (5 by default) and accepted in none of them, Philter Workspace
proposes a **Don't flag this again** rule. A proposal is never applied until a person
confirms it, and a value with a rule in any state, including a dismissed one, is not
proposed again. A reviewer can still ask for a dismissed rule again from the review page,
which proposes it anew.

## The Detection Rules page

**Detection Rules** in the sidebar lists:

- **Proposed** rules, with **Confirm** and **Dismiss**.
- Rules **In Philter**, with **Remove**. Removing a rule takes the term it added off
  Philter's list. A value already on the list, in any case, when the rule was confirmed is
  left there, and so is a variant in another case added to the list since; Philter still
  matches either one.
- **Dismissed** rules, with **Remove**, which lets the value be proposed again.

Each rule shows its value, the PII type of the span it was made from, why it was made,
who proposed it and when, the document it came from, and who confirmed or dismissed it
and when.

Administrators and auditors see every rule; a reviewer sees the rules made from documents
they can see. Auditors cannot change rules.

## Who may confirm

By default only administrators may confirm, dismiss, and remove rules. Under
**Admin → General → Detection rules**:

- **Reviewers may confirm** lets reviewers confirm, dismiss, and remove rules for the
  documents they can see. A reviewer's own rule is then confirmed as it is made.
  Because a rule applies to every group (see [Scope](#scope)), a reviewer who confirms
  or removes one changes what is redacted in every group's documents, including groups
  they do not belong to. Leave this setting off where groups must stay independent.
- **Refusals before a proposal** sets how many refusals in one batch, with no
  acceptance, propose a rule. `0` turns proposals off.

Groups do not limit rules because Philter keeps one never-redact and one always-redact
list per Philter user, and Philter Workspace redacts every batch as one user. The setting
is kept as it is, rather than limited to reviewers in every group, because it is off by
default, only an administrator can turn it on, and every confirmation and removal is
audited with who made it.

## Audit

Making, proposing, confirming, dismissing, and removing a rule are recorded in the audit
log as `DETECTION_RULE_CREATE`, `DETECTION_RULE_PROPOSE`, `DETECTION_RULE_CONFIRM`,
`DETECTION_RULE_DISMISS`, and `DETECTION_RULE_REMOVE`. The entries carry a keyed hash of
the value, lower-cased, and its length, never the value. The value is stored encrypted, like span text.
