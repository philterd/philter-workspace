# Roles and permissions

Philter Workspace has three roles. Every user has exactly one of them.

| Role | Purpose |
|------|---------|
| **`USER`** | Group-scoped reviewer. Can read and act on batches/documents in groups they belong to. The default for invited users. |
| **`ADMIN`** | Full access. Manages users, groups, integrations, batches, policies, and global settings. Has the `Limit to my groups` toggle to switch between global and group-scoped views. |
| **`AUDITOR`** | **Read-only counterpart to `ADMIN`.** Sees the same cross-group data an admin sees but cannot mutate state. Intended for compliance, legal, and analyst roles. |

In addition, any `USER` can be designated a **team lead** for one or more
groups. Team leadership is a per-group attribute of the group itself — not a
fourth role. See [Team leads](#team-leads) below. A `USER` can likewise be
designated an **approver** of a group, which is not a role either; see
[Approvers](#approvers).

## Why AUDITOR exists

Compliance reviews, internal audits, and after-the-fact incident investigations
all need someone who can look at every batch, every document's audit trail, and
every reviewer action — without that person also being able to approve, reject,
finalize, or reconfigure anything they look at. Granting `ADMIN` to that person
puts more authority in their hands than the role requires; granting `USER` and
adding them to every group is impractical and grants them write access within
those groups. `AUDITOR` is the dedicated role for this case: full read scope,
zero write scope.

## What an AUDITOR can do

An AUDITOR signs in like any other user and gets the same UI as an ADMIN, with
write affordances disabled. Specifically, they can:

- **See the global Document Queue** across every batch (the `Limit to my
  groups` toggle has no effect — auditors aren't in groups).
- **Open any batch and any document** for read-only review, including
  documents in groups they don't belong to. On the review page every span
  action, adding a span by selecting text, Ask AI, Explain, adding a comment,
  and the document's Approve, Reject, Unapprove, and Unreject are disabled or
  hidden, each disabled control saying "Auditors have read-only access"; span
  history, the navigator filters, comments, and Focus mode still work. Opening
  a document takes no review lock, so an auditor never locks a reviewer out.
- **Search** across every document body in the system.
- **Read every audit-log entry**, including actor emails, and **export** the
  per-document audit history as CSV, and the
  [audit trail with Philter's ledger](../admin/audit-log.md#audit-trail-with-philters-ledger)
  for a document or a time range. They also see a document's ledger chains on
  its Review page.
- **Read every reviewer action history** on any span, including which actor
  approved/rejected and any overturn reasons.
- **View the Reports page** — risk-score distribution, throughput, reviewer
  activity — for the full date range and every group.
- **Inspect every Background Job** (data-source ingest progress).
- **Browse the Admin → Users, Admin → Groups, Admin → Data Sources,
  Admin → Destinations, Admin → Philter, Admin → LLM-as-a-Judge,
  Admin → Compliance Profiles, Admin → Approval Rules, Admin → Weight Sets,
  Admin → Notifications, Admin → Security, Admin → Audit Log** pages — every
  page is visible.
- **Manage their own account** in Settings: change password and set review preferences.

## What an AUDITOR cannot do

The AUDITOR role refuses every non-self mutation. Concretely:

- Cannot upload a document, ingest from a data source, or queue any new work.
- Cannot approve / reject / finalize / unapprove / unreject a document or a
  span.
- Cannot create, edit, or close a batch.
- Cannot create, edit, or remove users, groups, data sources, destinations,
  Ollama instances, compliance profiles, approval rules, weight
  sets, or notification settings.
- Cannot run Admin → Philter's connection test.
- Cannot break a document review lock held by someone else.
- Cannot trigger an outbound test probe (e.g. the **Test** buttons on the
  Data Sources / Destinations / Philter / Ollama admin pages) — these issue
  POSTs and are blocked.

If an auditor clicks a write affordance that the UI happens to show them, the
server returns **HTTP 403** with the message *"Auditor accounts have read-only
access; mutating requests are not permitted."* The boundary is enforced
server-side regardless of what the page renders, so auditor accounts cannot be
used to write data even by direct API call.

## Feature matrix

A check (✓) means the role can do the action; a dash (—) means they cannot.

The **Team lead** column shows what a `USER` who has been designated as a lead
for at least one group can do *within the groups they lead*. Outside those
groups, a team lead has the same permissions as the **USER** column.

### Reading and reviewing

| Feature | USER | Team lead | AUDITOR | ADMIN |
|---------|:----:|:---------:|:-------:|:-----:|
| Sign in / sign out | ✓ | ✓ | ✓ | ✓ |
| See own group's batches and documents | ✓ | ✓ | ✓ (sees all) | ✓ |
| See **all** batches and documents (cross-group) | — | — | ✓ | ✓ |
| Search documents (own group) | ✓ | ✓ | ✓ (all) | ✓ |
| Search documents (cross-group) | — | — | ✓ | ✓ |
| View document spans, history, certificate | ✓ (own group) | ✓ (own group) | ✓ (all) | ✓ |
| **Batches** link in the sidebar | — | ✓ | ✓ | ✓ |
| Export per-document audit history as CSV | — | — | ✓ | ✓ |
| Read the global Audit Log | — | — | ✓ | ✓ |
| Read the Reports dashboard | — | — | ✓ | ✓ |
| See every Background Job | — | — | ✓ | ✓ |

### Reviewing actions

| Feature | USER | Team lead | AUDITOR | ADMIN |
|---------|:----:|:---------:|:-------:|:-----:|
| Approve / reject a span | ✓ (own group) | ✓ (own group) | — | ✓ |
| Approve / reject a document | ✓ (own group) | ✓ (own group) | — | ✓ |
| Finalize a document | ✓ (own group) | ✓ (own group) | — | ✓ |
| Add a manual span | ✓ (own group) | ✓ (own group) | — | ✓ |
| Reopen (unapprove / unreject) a document | ✓ (own group) | ✓ (own group) | — | ✓ |
| Break someone else's review lock | Approvers of the group only | Approvers of the group only | No | ✓ |
| Add a comment to a document | ✓ (own group) | ✓ (own group) | — | ✓ |
| Request an LLM second opinion | ✓ (own group) | ✓ (own group) | — | ✓ |
| Make or propose a [detection rule](../user-guide/detection-rules.md) | ✓ (own group) | ✓ (own group) | No | ✓ |
| Confirm, dismiss, or remove a detection rule | Only if allowed in Admin → General (own group's documents; the rule applies to every group) | Only if allowed in Admin → General (own group's documents; the rule applies to every group) | No | ✓ |

### Batch operations

| Feature | USER | Team lead | AUDITOR | ADMIN |
|---------|:----:|:---------:|:-------:|:-----:|
| Create a batch in a led group | — | ✓ (led groups only) | — | ✓ |
| Change a batch's policy | — | ✓ (led groups only) | — | ✓ |
| Change a batch's domain | — | ✓ (led groups only) | — | ✓ |
| Change a batch's weight set | — | ✓ (led groups only) | — | ✓ |
| Edit a batch's review thresholds | — | ✓ (led groups only) | — | ✓ |
| Close a batch | — | ✓ (led groups only) | — | ✓ |
| Move a batch to a different group | — | — | — | ✓ |

### Ingest and integrations

| Feature | USER | Team lead | AUDITOR | ADMIN |
|---------|:----:|:---------:|:-------:|:-----:|
| Upload a document via the web form | ✓ (own group) | ✓ (own group) | — | ✓ |
| Ingest from a configured data source | ✓ (own group) | ✓ (own group) | — | ✓ |
| Configure a data source / destination | — | — | — | ✓ |
| Configure a Philter / Ollama instance | — | — | — | ✓ |
| Test an outbound integration (Test buttons) | — | — | — | ✓ |

### Administration

| Feature | USER | Team lead | AUDITOR | ADMIN |
|---------|:----:|:---------:|:-------:|:-----:|
| View the Admin → Users page | — | — | ✓ | ✓ |
| Invite, edit, delete users | — | — | — | ✓ |
| Promote / demote a user's role | — | — | — | ✓ |
| Manage groups (including assigning leads and approvers) | No | No | No | ✓ |
| Test the connection to Philter (Admin → Philter) | — | — | — | ✓ |
| Configure approval rules / weight sets / compliance profiles | — | — | — | ✓ |
| Change global security settings (Require MFA, etc.) | — | — | — | ✓ |

### Self-service

| Feature | USER | Team lead | AUDITOR | ADMIN |
|---------|:----:|:---------:|:-------:|:-----:|
| Change own password | ✓ | ✓ | ✓ | ✓ |
| Set own review preferences | ✓ | ✓ | ✓ | ✓ |

## Group membership

`AUDITOR` does not require group membership and group membership does not
grant an auditor any additional permissions — the role's read-everything
scope already covers every group. Adding an auditor to a group is harmless
but pointless.

## Assigning the AUDITOR role

The role lives in the same field as USER and ADMIN. To assign it:

1. Sign in as an admin.
2. Go to **Admin → Users**.
3. On the person's row, under **In Philter Workspace**, tick **Auditor (read-only)** and save.

A Philter administrator is always a Philter Workspace `ADMIN`, so only a Philter `user` can be an auditor.
The person's session ends at its next check, and they sign in again with the new role. The change
is recorded in the audit log as a `USER_UPDATE` event with the new role.

## Team leads

A team lead is a `USER` whom an admin has designated as a leader of a specific
group. Leadership is *per-group*: a single user can lead group A while being
just a regular member (or no member at all) of group B. Their elevated
authority follows them into group A and only group A.

### Why team leads exist

Day-to-day batch operations (creating a batch, setting its policy
or weight set, closing a finalized batch) don't require site-wide admin
authority, but they do require more than what a typical reviewer needs.
Routing every such request through an admin makes the admin a bottleneck. A
team lead is the trusted operational contact for a single group: an admin
designates them once, and they carry the day-to-day batch authority for that
group without being able to touch any other group or any global setting.

### What a team lead can do (in groups they lead)

- **Create a batch** assigned to a group they lead.
- **Change a batch's policy**, **domain**, **weight set**, or
  **review thresholds**.
- **Close** a finalized or rejected batch.
- **See the Batches link in the sidebar.** Regular `USER`s do not see it
  unless they lead at least one group.

In groups they don't lead, a team lead has the same permissions as any
`USER` — they can read and act on documents in groups they belong to, but
not perform any of the operations above.

### What a team lead cannot do

- **Move a batch to a different group.** Reassignment crosses tenant
  boundaries and is admin-only.
- **Anything outside the Batches page.** Team leads cannot manage users,
  groups, data sources, destinations, Ollama instances, policies,
  approval rules, weight sets, or any global setting.
- **Lead a group they don't belong to.** Leadership requires group
  membership; the leader picker on the Admin → Groups page only offers
  current members of the group as candidates.

### Assigning a team lead

1. Sign in as an admin.
2. Go to **Admin → Groups** and either create a group or click **Edit** on
   an existing group.
3. In the **Members** section, each row has two checkboxes: **Member** and
   **Lead**. Tick **Lead** for any user who should lead this group. (Members
   who are not also marked as leads remain regular users in the group.)
4. Save.

The change takes effect on the leader's next request — they immediately see
the **Batches** link in the sidebar and can perform the operations above on
batches assigned to that group.

To remove a team lead, edit the group and untick the **Lead** checkbox for
that user. Untick **Member** as well to remove them from the group entirely.

## Approvers

An approver is a `USER` whom an administrator has designated as an approver of
a specific group: the supervisor who gives a second sign-off, settles disputed
decisions, and frees up work. Like leadership, it is per-group and not a role:
it applies to the documents of batches in that group and nowhere else.
Administrators can do everything an approver can, in every group. Leadership and
approval are separate; a team lead is not an approver unless designated one.

### What an approver can do (in groups they approve for)

- **Give the second approval an approval rule set reserves for an approver.**
  See [Second approval from an approver](../admin/rules.md#second-approval-from-an-approver).
- **Adjudicate a span**: make the final decision to accept or refuse it,
  including a span awaiting a second opinion. The decision is recorded as the
  approver's, and only an approver of the group can change the span afterwards.
  See [Adjudicating a span](../user-guide/reviewing.md#adjudicating-a-span).
- **Release a document** another reviewer holds (**Break Lock** on the review
  page), so someone else in the group can take it.

| Feature | Approver | USER | AUDITOR | ADMIN |
|---------|:--------:|:----:|:-------:|:-----:|
| Give a second approval reserved for an approver | ✓ (own group) | No | No | ✓ |
| Adjudicate a span | ✓ (own group) | No | No | ✓ |
| Change a span an approver adjudicated | ✓ (own group) | No | No | ✓ |
| Release a document another reviewer holds | ✓ (own group) | No | No | ✓ |

### What an approver cannot do

An approver has no administrator permissions: they cannot manage users,
groups, approval rules, or any configuration, and approving for one group
gives nothing in another. Work is shared by group: an approver releases a
document for anyone in the group to take, rather than assigning it to a
named reviewer.

### Designating an approver

1. Sign in as an admin.
2. Go to **Admin → Groups** and create a group or edit one.
3. Tick **Approver** beside each member who should approve for the group. An
   approver must also be a member; **Approver** without **Member** is ignored.
4. Save.

The change applies on the approver's next request. The audit log records the
group's approver count on `GROUP_CREATE` and `GROUP_UPDATE`.

## Auditing the auditors

Every AUDITOR action that touches the database — even reads of sensitive
endpoints like the per-document history CSV — is logged in the
[Audit Log](../admin/audit-log.md) with the auditor's email and timestamp.
Auditors cannot edit or delete audit log entries. The log itself is the
authoritative record of what each auditor saw and when.
