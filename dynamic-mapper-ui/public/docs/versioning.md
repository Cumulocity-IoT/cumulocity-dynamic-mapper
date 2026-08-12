---
title: Versioning mappings
---

### Overview {#versioning-overview}

Every mapping keeps a history of **versions** using **semantic versioning** (`MAJOR.MINOR.PATCH`, e.g.
<span class="label label-default">v1.2.0</span>). Editing a mapping no longer changes the running configuration
directly — instead your changes are saved to a **draft**. You then **publish** the draft to create a new immutable
version, choosing the version label yourself, and **activate** the version you want to run. This lets you change a
mapping safely, review what changed over time, and roll back to a known-good configuration at any point.

Key rule: **at most one version of a mapping is active at any time.** Activating a version automatically
deactivates the previously active one, so there is never any ambiguity about which configuration is processing
messages.

### The version lifecycle {#versioning-lifecycle}

The full flow when working with a mapping is:

1. **Edit → Save** — your changes are stored in the mapping's single **draft**. The active configuration keeps
   running unchanged. A <span class="label label-info">draft</span> badge appears in the mapping list.
2. **Publish** the draft — a dialog opens where you choose the version label and an optional **change note**. Use
   the **Patch**, **Minor**, or **Major** bump buttons to auto-suggest the next semver label, or type any
   `MAJOR.MINOR.PATCH` string directly. Once confirmed, the draft becomes a new immutable version. Publishing does
   not activate the version.
3. **Activate** a version — its configuration is loaded into the running mapping. To **roll back**, simply
   activate an older version; newer versions are kept and remain available.

:::info Note
The first time you publish or activate a mapping that has no version history yet, its current active configuration
is automatically captured as **1.0.0**, so no history is lost. Existing mappings that previously used sequential
integer version numbers (1, 2, 3…) are automatically migrated to semver format (`1.0.0`, `2.0.0`, etc.) on first
access.
:::

### Choosing the right version bump {#versioning-semver}

When publishing a draft, the publish dialog offers three bump buttons that pre-fill the version field based on the
highest version published so far:

| Button | When to use | Example |
|---|---|---|
| **Patch** | Backwards-compatible bug fix or minor tweak (no change to the data model) | `1.2.0` → `1.2.1` |
| **Minor** | New optional substitution, new source field, or other additive change | `1.2.1` → `1.3.0` |
| **Major** | Breaking change: different topic pattern, removed field, restructured template | `1.3.0` → `2.0.0` |

You can also type any valid `MAJOR.MINOR.PATCH` string directly into the version field. Version labels must be
unique within a mapping line — a label that was previously used cannot be reused after deletion.

### The Versions view {#versioning-view}

Open the version history from the mapping list: click the **⋮** (actions) menu of a mapping and select
**Versions**. A drawer lists every record of that mapping in a single grid — the current draft and all published
versions — each tagged with its **State**:

- <span class="label label-primary">active</span> — the version currently running.
- <span class="label label-default">published</span> — an immutable version you can activate or delete.
- <span class="label label-info">draft</span> — unpublished edits waiting to be published.

Versions are listed in descending semver order (newest first). Each row offers contextual actions in its **⋮**
menu: **Publish** (on the draft, opens the version-picker dialog), **Activate** and **Delete** (on inactive
published versions), and inline note editing (on any published version). The active version cannot be deleted —
activate another version first.

![Versions drawer](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Versions_Drawer.png "The Versions drawer for a mapping.")

### Version and draft indicators in the mapping list {#versioning-status-badge}

The **Status** column of the inbound/outbound mapping list shows, at a glance, the **active version** of each
mapping (for example <span class="label label-default">v1.2.0</span>) and a <span class="label label-info">draft</span>
badge when the mapping has unpublished changes.

![Version and draft badges in the mapping list](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Versions_Status_Badge.png "The mapping list Status column showing the active version badge and a draft badge.")

### Change notes {#versioning-labels}

A **change note** is an optional free-text description of what a version contains or why it was created. You set
it in the publish dialog when creating the version, and you can edit it later for any published version directly
in the Versions drawer. The note is the only field of a published version that can be changed after publishing —
the version label, snapshot, and all other fields are immutable.

### Configuring how many versions are kept {#versioning-retention}

To bound storage, only the most recent versions of each mapping are retained. The limit is configured per tenant
under **Configuration → Service configuration → Caching** in the field **Number of mapping versions to retain**
(default 10). When a new version is published, versions older than this limit are deleted, ordered by publish date
— the **active version is never deleted**, even if it falls outside the retention window.

![Mapping version retention setting](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Versions_Retention_Config.png "The \"Number of mapping versions to retain\" setting under Service configuration → Caching.")

### Relationship in the inventory {#versioning-inventory}

Each version is stored as its own managed object (type `d11r_mapping_version`) and is registered as a **child
addition** of the mapping's managed object. This makes the mapping → versions relationship navigable directly in
the Cumulocity inventory and from external tooling, independently of the mapper UI. The `version` field on both the
mapping managed object and each version record uses the `MAJOR.MINOR.PATCH` format.
