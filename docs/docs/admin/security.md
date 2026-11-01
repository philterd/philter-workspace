# Security settings

The **Security** tab in Admin (`/admin/security`) contains policies that apply to all users
across the deployment.

## Multi-factor authentication

People sign in through Philter, which handles their MFA, so this tab has no MFA setting. To require
MFA, turn on **Everyone must set up MFA** under [Admin → Philter → Settings](philter-settings.md)
(Philter's `mfaRequired` setting); each person then enrolls at their next sign-in. With
Philter's `mfaAvailable` setting on, people can also set up and remove MFA themselves under
[Settings](../user-guide/settings.md#multi-factor-authentication). See
[Sign-in through Philter](../security.md#sign-in-through-philter).

## Data-source host allow-list

Philter Workspace ships with a host allow-list that gates outbound connections to
external systems (OpenSearch, Elasticsearch, S3, relational databases, Ollama, and
any other URL an admin types into a Test button or saves on a data source).
The allow-list combines two protections: a **default-deny on private-range
addresses** (RFC-1918, loopback, link-local, carrier-grade NAT, and the IPv6
forms that carry them) and an **operator-supplied allow-list of hosts**
configured via the `philter-workspace.data-sources.allowed-hosts` property. Both
are documented in
[Data sources → Trust model and host allow-list](data-sources.md#trust-model-and-host-allow-list),
including when each endpoint is checked.

The **Enable host allow-list** checkbox on **Admin → Security** is the
master switch for the pattern check on public hosts. It never turns off the
private-range default-deny.

| State | Behavior |
|---|---|
| **Enabled** (default) | Outbound endpoints are checked against the configured allow-list and the private-range default-deny. Hosts that don't match are refused with the *"Endpoint host is not on the data-source allow-list"* error. |
| **Disabled** | Any well-formed public host is accepted, whatever the configured patterns. Private-range addresses are still refused unless `philter-workspace.data-sources.allowed-hosts` names them. |

The setting is read at request time, so toggling it takes effect on the
next request without a restart.

### Enabling the allow-list

This is the default state for new installs. To re-enable after disabling:

1. Navigate to **Admin → Security**.
2. Check **Enable host allow-list**.
3. Click **Save**.

### Disabling the allow-list

Uncheck **Enable host allow-list** and click **Save**. A red banner
appears on the Security page confirming the switch is off.

!!! danger "Disabling is not recommended"
    The configured patterns limit which hosts an admin, or anyone who has
    compromised an admin session, can point a data source at. With the
    allow-list off, misconfigured or hostile data-source rows can be saved
    against any public endpoint, since the URL is no longer checked against
    the curated list. Internal infrastructure on private-range addresses,
    including cloud instance-metadata endpoints (`169.254.169.254`,
    `100.100.100.200`, `fd00:ec2::254`), stays refused either way.

    Disable only as a temporary measure for a known-good, short-lived
    troubleshooting session. Configure
    `philter-workspace.data-sources.allowed-hosts` to cover the legitimate hosts
    you need to reach, and turn the allow-list back on as soon as
    practical. Toggling produces a `SECURITY_SETTINGS_CHANGE` audit row
    so the off-window is auditable after the fact.

## Audit trail

All changes to security settings are recorded in the [audit log](audit-log.md):

| Event | Trigger |
|---|---|
| `SECURITY_SETTINGS_CHANGE` | Admin saves the Security settings form (logged whether or not the value changed); payload names the switch (`hostAllowListEnabled`) and its previous value |
