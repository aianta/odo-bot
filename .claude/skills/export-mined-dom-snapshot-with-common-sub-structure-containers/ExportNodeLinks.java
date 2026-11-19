import ca.ualberta.odobot.modelconstruction.impl.TagAndAttributeStrategy;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Produces the nodeLinks graph OdoBot builds for a DOMSnapshot when mining common sub-structures.
 *
 * Calls OdoBot's own TagAndAttributeStrategy.toNodeLinks() (the method ModelConstructionVerticle's
 * makeMinhashLSHForSnapshotNodes() uses), so node ids, colors, robustXpaths and oxps are exactly the
 * ones the mining run saw, and the node_id column of common_substructures refers to them.
 *
 * Usage: java -cp <odobot classes + deps> ExportNodeLinks.java <snapshot.html> <out-node-links.json>
 */
public class ExportNodeLinks {
    public static void main(String[] args) throws Exception {
        String html = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);

        // toNodeLinks() never uses the Vertx instance; it is only handed to NodeLinksVisitor and stored.
        TagAndAttributeStrategy strategy = new TagAndAttributeStrategy(null);
        JsonObject graph = strategy.toNodeLinks(html).result();

        Files.writeString(Path.of(args[1]), graph.encodePrettily(), StandardCharsets.UTF_8);
        System.out.println("nodes=" + graph.getJsonArray("nodes").size() + " links=" + graph.getJsonArray("links").size());
        System.exit(0);
    }
}
