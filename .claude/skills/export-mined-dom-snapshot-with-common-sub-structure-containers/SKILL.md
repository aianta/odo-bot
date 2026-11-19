---
name: export-mined-dom-snapshot-with-common-sub-structure-containers
description: Export one mined DOMSnapshot from odobot.db together with OdoBot's nodeLinks graph of it (node ids and node colors as produced by TagAndAttributeStrategy.toNodeLinks) and the common sub-structure containers and common sub-structures mined from it, all keyed to the same node ids, to an output folder of the user's choice. Use when the user wants a snapshot's HTML, its nodeLinks / DOM graph, or its containers and sub-structures as files (e.g. for visualizations or presentation animations), or asks which snapshots are most referenced by common_substructure_containers / common_substructures.
---

# Export a mined DOMSnapshot with its common sub-structures

`export_snapshot.py` (next to this file) writes four files for one snapshot id into an output folder:

| File | Content |
|---|---|
| `<id>.html` | `dom_snapshots.snapshot`, byte for byte |
| `<id>-node-links.json` | `{nodes:[{id, color, robustXpath?, oxp?}], links:[{source, target}]}`, OdoBot's nodeLinks graph |
| `<id>-containers.json` | `[{clusterId, xpath, nodeId, html}]`, one per `common_substructure_containers` row |
| `<id>-sub-structures.json` | `[{clusterId, xpath, originalXpath, nodeId, containerNodeId, html}]`, one per `common_substructures` row |

Every `nodeId` / `containerNodeId` is an `id` in the node-links file.

```
python -I .claude/skills/export-mined-dom-snapshot-with-common-sub-structure-containers/export_snapshot.py <snapshot-id> <out-dir> [--db <path>] [--clustering <id>] [--odobot <odo-bot root>]
```

Ask the user for the output folder if they have not given one. `--db` defaults to `db/odobot.db` (the root `odobot.db` is a near-empty stub). The db is opened read-only.

## How the ids are kept consistent

- The graph comes from **OdoBot's own compiled code**: `ExportNodeLinks.java` calls `TagAndAttributeStrategy.toNodeLinks()`, the method `ModelConstructionVerticle.makeMinhashLSHForSnapshotNodes()` uses when mining. That method parses with JSoup, tags each element with its `oxp` (pre-cleaning xpath), then runs `HTMLCleaningTools.clean`, `BlankRemovingVisitor`, `PruningVisitor`, numbers the nodes in pre-order with `LabelingVisitor`, and colors them with `nodeToLabel` (a tag + attribute label for elements, and BLANK / NUMERIC / SHORT_TEXT / LONG_TEXT for text). Do not re-implement it; a re-implementation drifts and the ids stop matching.
- `common_substructures.node_id` is a node id from that graph. The script checks each one against the node's `robustXpath` and `oxp`, and checks that the node's parent is the container.
- Containers have no saved node id. The script finds each one by its `robust_xpath` (`computeXpathNoRoot` on the same cleaned and pruned DOM), which has to match exactly one graph node.
- If any row fails to line up, the script prints the mismatches and does not write the container or sub-structure files.

## Requirements

- `build/classes/java/main` must be compiled (`gradlew compileJava`), along with `libs/jsoup-1.19.1-SNAPSHOT.jar`. vertx-core, jackson-core, slf4j-api and netty come from `~/.gradle/caches`. If cleaning or labeling code has changed since the mining run, ids can drift; the checks will catch that.
- Java 11 or later (it runs `ExportNodeLinks.java` as a single-file program). The SLF4J "no providers" warnings it prints are harmless.

## Finding snapshots worth exporting

Most referenced snapshots (one clustering, `c678c0ce-…`, as of 2026-10-28):

```sql
SELECT snapshot_id, count(*) n FROM common_substructure_containers GROUP BY 1 ORDER BY n DESC LIMIT 10;  -- top: 6a31c4cdbc5e78f119b86d3d (39)
SELECT snapshot_id, count(*) n FROM common_substructures          GROUP BY 1 ORDER BY n DESC LIMIT 10;  -- top: 6a31c7edbc5e78f119b878af (138)
```

`cluster_id` is global across snapshots (DBSCAN over MinHash). Each snapshot has at most one container per cluster.
