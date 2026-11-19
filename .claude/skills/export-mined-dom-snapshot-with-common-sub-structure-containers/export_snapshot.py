"""Export a mined DOMSnapshot, its nodeLinks graph, and its common sub-structure containers and sub-structures.

Writes to <out-dir>:
  <id>.html                 the snapshot as stored in dom_snapshots.snapshot
  <id>-node-links.json      OdoBot's nodeLinks graph (TagAndAttributeStrategy.toNodeLinks, via ExportNodeLinks.java)
  <id>-containers.json      [{clusterId, xpath, nodeId, html}]
  <id>-sub-structures.json  [{clusterId, xpath, originalXpath, nodeId, containerNodeId, html}]

Every nodeId refers to an "id" in <id>-node-links.json. The script fails rather than writing
files whose ids do not line up with the graph.

Usage:
  python export_snapshot.py <snapshot-id> <out-dir> [--odobot <odo-bot root>] [--db <path>] [--clustering <id>]
"""
import argparse
import glob
import json
import os
import pathlib
import sqlite3
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent

# Jars the compiled OdoBot classes need for toNodeLinks(), found in the Gradle cache.
GRADLE_DEPS = [
    ("io.vertx", "vertx-core"),
    ("com.fasterxml.jackson.core", "jackson-core"),
    ("org.slf4j", "slf4j-api"),
    ("io.netty", "netty-common"),
    ("io.netty", "netty-buffer"),
    ("io.netty", "netty-transport"),
    ("io.netty", "netty-handler"),
    ("io.netty", "netty-codec"),
    ("io.netty", "netty-resolver"),
]


def version_key(v):
    return [int(p) if p.isdigit() else p for p in v.replace("-", ".").split(".")]


def find_jar(group, artifact):
    cache = pathlib.Path.home() / ".gradle/caches/modules-2/files-2.1" / group / artifact
    versions = sorted((d.name for d in cache.glob("*") if d.is_dir()), key=version_key) if cache.exists() else []
    for v in reversed(versions):
        jars = [j for j in glob.glob(str(cache / v / "*" / f"{artifact}-{v}.jar"))]
        if jars:
            return jars[0]
    return None


def classpath(odobot):
    classes = odobot / "build/classes/java/main"
    if not (classes / "ca/ualberta/odobot/modelconstruction/impl/TagAndAttributeStrategy.class").exists():
        sys.exit(f"No compiled OdoBot classes under {classes}; run gradlew compileJava in {odobot} first.")
    entries = [str(classes)] + glob.glob(str(odobot / "libs/jsoup-*.jar"))
    for group, artifact in GRADLE_DEPS:
        jar = find_jar(group, artifact)
        if jar is None:
            sys.exit(f"{group}:{artifact} not found in the Gradle cache; build OdoBot once with gradlew.")
        entries.append(jar)
    return os.pathsep.join(entries)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("snapshot_id")
    ap.add_argument("out_dir")
    ap.add_argument("--odobot", default=None, help="odo-bot repo root (default: found from this file's location)")
    ap.add_argument("--db", default=None, help="SQLite db (default: <odobot>/db/odobot.db)")
    ap.add_argument("--clustering", default=None, help="clustering_id (default: the only/first one that mined the snapshot)")
    args = ap.parse_args()

    odobot = pathlib.Path(args.odobot).resolve() if args.odobot else HERE.parents[2]
    db = pathlib.Path(args.db).resolve() if args.db else odobot / "db/odobot.db"
    out = pathlib.Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    sid = args.snapshot_id

    con = sqlite3.connect(db.as_uri() + "?mode=ro", uri=True)
    row = con.execute("SELECT snapshot FROM dom_snapshots WHERE id = ?", (sid,)).fetchone()
    if row is None:
        sys.exit(f"Snapshot {sid} is not in dom_snapshots.")
    html = row[0]

    clusterings = [r[0] for r in con.execute(
        "SELECT DISTINCT clustering_id FROM common_substructure_containers WHERE snapshot_id = ?", (sid,))]
    if not clusterings:
        sys.exit(f"Snapshot {sid} has no common_substructure_containers rows (it was not mined).")
    clustering = args.clustering or clusterings[0]
    if len(clusterings) > 1 and not args.clustering:
        print(f"warning: snapshot mined by {len(clusterings)} clusterings, using {clustering}; pass --clustering to choose")

    containers = con.execute(
        "SELECT cluster_id, robust_xpath, html FROM common_substructure_containers "
        "WHERE clustering_id = ? AND snapshot_id = ? ORDER BY CAST(cluster_id AS INTEGER)", (clustering, sid)).fetchall()
    subs = con.execute(
        "SELECT cluster_id, node_id, robust_xpath, original_xpath, html FROM common_substructures "
        "WHERE clustering_id = ? AND snapshot_id = ? ORDER BY CAST(node_id AS INTEGER)", (clustering, sid)).fetchall()

    # 1. Snapshot HTML, byte for byte as stored.
    html_path = out / f"{sid}.html"
    html_path.write_text(html, encoding="utf-8", newline="")

    # 2. nodeLinks via OdoBot's own code.
    links_path = out / f"{sid}-node-links.json"
    subprocess.run(["java", "-cp", classpath(odobot), str(HERE / "ExportNodeLinks.java"), str(html_path), str(links_path)],
                   check=True, stderr=subprocess.DEVNULL)
    graph = json.loads(links_path.read_text(encoding="utf-8"))
    nodes = {n["id"]: n for n in graph["nodes"]}
    parent = {l["target"]: l["source"] for l in graph["links"]}
    by_xpath = {}
    for n in graph["nodes"]:
        if "robustXpath" in n:
            by_xpath.setdefault(n["robustXpath"], []).append(n["id"])

    errors = []

    # 3. Containers: located by their xpath (computeXpathNoRoot on the same cleaned and pruned DOM).
    container_out, container_node = [], {}
    for cluster_id, xpath, chtml in containers:
        ids = by_xpath.get(xpath, [])
        if len(ids) != 1:
            errors.append(f"container cluster {cluster_id}: xpath {xpath} matches {len(ids)} nodes")
            continue
        container_node[cluster_id] = ids[0]
        container_out.append({"clusterId": cluster_id, "xpath": xpath, "nodeId": ids[0], "html": chtml})

    # 4. Sub-structures: node_id is the nodeLinks id from mining; check it against xpath, oxp and parent.
    sub_out = []
    for cluster_id, node_id, xpath, oxp, shtml in subs:
        n = nodes.get(node_id)
        if n is None or n.get("robustXpath") != xpath or (oxp and n.get("oxp") != oxp):
            errors.append(f"sub-structure node {node_id} (cluster {cluster_id}): graph node does not match xpath {xpath} / oxp {oxp}")
            continue
        cnode = container_node.get(cluster_id)
        if cnode is None or parent.get(node_id) != cnode:
            errors.append(f"sub-structure node {node_id} (cluster {cluster_id}): parent {parent.get(node_id)} is not container node {cnode}")
            continue
        sub_out.append({"clusterId": cluster_id, "xpath": xpath, "originalXpath": oxp, "nodeId": node_id,
                        "containerNodeId": cnode, "html": shtml})

    if errors:
        print("\n".join(errors[:50]))
        sys.exit(f"{len(errors)} rows did not line up with the nodeLinks graph; container/sub-structure files not written.")

    (out / f"{sid}-containers.json").write_text(json.dumps(container_out, indent=2), encoding="utf-8")
    (out / f"{sid}-sub-structures.json").write_text(json.dumps(sub_out, indent=2), encoding="utf-8")
    print(f"clustering={clustering} nodes={len(nodes)} containers={len(container_out)} sub-structures={len(sub_out)} -> {out}")


if __name__ == "__main__":
    main()
