# Mapping tree

The mapping tree is the structure the service uses to find the inbound mappings for a topic. The
*Monitoring → Hierarchy mapping* page shows it, so a user can see which mapping a message on a given topic
reaches and why two mappings overlap. It is a view of the lookup, not a second source of truth.

---

## Requirements

**What it is for.** Answering "which mappings does this topic reach?" and "where does my mapping sit?"
without sending a message.

- **The tree shows inbound mappings only.** Outbound mappings are not topic based and never appear.
- **The tree is by mapping topic.** One level per topic segment. A mapping sits at the node of its
  topic; a node can hold several mappings, so two mappings with the same topic appear together.
- **Wildcards are part of the structure.** A `+` or `#` in a mapping topic is a node like any other. A
  message topic reaches the exact node, the `+` node on the same level, and a `#` node at or above it. A topic
  `a/b/#` also matches `a/b`.
- **The tree shows what the lookup uses.** A mapping is in the tree when it is saved, whether it is active
  or not and whichever connector it is deployed to. The tree is tenant-wide.
- **Being in the tree is not being executed.** After the lookup, only mappings that are active and effectively
  deployed on the receiving connector run ([deployment-map.md](deployment-map.md)). The page does not show
  active state or deployment.
- **It follows changes at once.** Creating, changing the topic of, or deleting a mapping updates the tree;
  a change of topic never leaves the mapping missing from the tree, so a message arriving at that moment is
  not lost.
- **It is read-only.** The page offers refresh and the JSON viewer's search and expand, no editing.
- **Everyone with access to the application may open it.** The page shows the mapping definitions.

**Non-goals.** No outbound tree, no per-connector view, no test input ("which mapping would match this
topic") on the page, and no live push; the page loads on open and on refresh.

---

## Implementation

### Parts

| Part | Role |
|---|---|
| [`MappingTreeNode`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/MappingTreeNode.java) | The tree: root, inner nodes, mapping nodes, resolve, add, replace, delete. |
| [`MappingCacheManager`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/mapping/cache/MappingCacheManager.java) | Owns one tree per tenant inside its inbound state; builds and swaps it. |
| [`MappingResolverService`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/mapping/resolver/MappingResolverService.java) | `resolveInbound(tenant, topic)` calls the tree. |
| [`MonitoringController`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/controller/MonitoringController.java) | `GET /monitoring/tree` returns the tenant's root node. It is in the monitoring controller, not the mapping controller. |
| [`MappingTreeNodeJackson3Serializer`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/MappingTreeNodeJackson3Serializer.java) | Writes a node without following `parentNode`. |
| [`mapping-tree`](../../dynamic-mapper-ui/src/mapping-tree) | The page. |

### Relation to resolution

The page and the lookup read the same object. `InboundMessageDispatcher` calls
`MappingService.resolveMappingInbound`, which reaches `MappingTreeNode.resolveMapping(topic)` through the
resolver service and cache manager. `GET /monitoring/tree` returns `getResolverMappingInbound(tenant)`, the live
root of that tree. The router then filters the result by active and deployed.

Tree levels include separators: a topic is split with its `/` characters as levels of their own
(`Mapping.splitTopicIncludingSeparatorAsList`). `resolveTopicPath` walks one level at a time: exact children,
then `+` children, then `#` children, which end the walk. At the end of the topic it also takes a `#` below the
`/` child, which is what makes `a/b/#` match `a/b`. Mapping nodes have a mapping and no useful children; inner
nodes have children and no mapping. A topic that ends on an inner node resolves to nothing (logged at debug).

### Maintenance

| Event | Tree operation |
|---|---|
| Tenant start and `RELOAD_MAPPINGS` | `rebuildInboundCache` builds a new tree off to the side and swaps it in with one write. A mapping that cannot be added is logged and skipped. |
| Create or update | `replaceMapping(old, new)` deletes and adds under one write lock; the old mapping may have a different topic. |
| Delete | `deleteMapping` removes the node and prunes inner nodes that became empty. |

One `ReentrantReadWriteLock` guards the whole tree and is shared by all nodes. Mappings with a null or blank
topic are skipped with a warning. Two inner nodes at the same level and key are an error
("Multiple inner nodes are registered"). The serializer reads children without taking the lock; children
lists are `CopyOnWriteArrayList`.

### What the endpoint returns

Each node has `depthIndex`, `level`, `nodeId`, `isMappingNode`, `parentNode` (the parent's absolute path or
the string `"null"`), `absolutePath`, and either `mapping` (the complete mapping, including code) or
`childNodes`, a map from level to a list of nodes.

### UI

[`MappingTreeService.loadMappingTree`](../../dynamic-mapper-ui/src/mapping-tree/mapping-tree.service.ts) calls
`GET /monitoring/tree` and reduces the response with a JSONata expression (`cleanJSONata`) to nested
objects keyed by absolute path, with the mapping at the leaves. The page
([`MappingTreeComponent`](../../dynamic-mapper-ui/src/mapping-tree/mapping-tree.component.ts)) shows it in a
read-only tree viewer. The route resolves the tree before the page opens, and the navigation entry is
*Monitoring → Hierarchy mapping*.

### Traps

- **A tree level is a segment or a separator.** Expect `/` nodes in the raw JSON; the page hides them.
- **Do not hold the root.** A rebuild installs a new root, so a reference from before a reload is stale.
- **The raw endpoint returns full mappings, code included.** The page shows them to any user who can open it.
- **The tree says nothing about deployment.** A mapping in the tree can still be inert on every connector.
- **A tree failure is invisible to the user.** If serialization fails mid-response the UI reports an invalid
  response; if one mapping cannot be added at rebuild, only the log shows it, and that mapping never matches.
