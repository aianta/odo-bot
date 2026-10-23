package ca.ualberta.odobot.trajectory;

import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which OdoBot and OdoX produced a trajectory, for reproducibility.
 */
public final class BuildInfo {

    private static final Logger log = LoggerFactory.getLogger(BuildInfo.class);

    private static volatile String odobotCommit;

    private static final Map<Path, String> odoxVersions = new ConcurrentHashMap<>();

    private BuildInfo() {
    }

    /**
     * @return the commit the running jar was built from (its {@code Odobot-Commit} manifest attribute, see build.gradle),
     * else the {@code ODOBOT_COMMIT} environment variable, else null.
     */
    public static String odobotCommit() {
        if (odobotCommit == null) {
            String commit = null;
            try {
                Enumeration<URL> manifests = BuildInfo.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
                while (commit == null && manifests.hasMoreElements()) {
                    try (InputStream in = manifests.nextElement().openStream()) {
                        commit = new Manifest(in).getMainAttributes().getValue("Odobot-Commit");
                    }
                }
            } catch (IOException e) {
                log.debug("Could not read the jar manifest: {}", e.getMessage());
            }
            if (commit == null) {
                commit = System.getenv("ODOBOT_COMMIT");
            }
            odobotCommit = commit == null ? "" : commit;
        }
        return odobotCommit.isEmpty() ? null : odobotCommit;
    }

    /**
     * @param xpi the OdoX extension the task's browser loaded, or null.
     * @return the {@code version} of its manifest.json and the start of the file's sha256, which tells builds of the
     * same version apart, e.g. {@code 0.1+3f2a9c1d0b7e}; null if it can't be read.
     */
    public static String odoxVersion(String xpi) {
        if (xpi == null) {
            return null;
        }
        Path path = Path.of(xpi).toAbsolutePath().normalize();
        String version = odoxVersions.computeIfAbsent(path, p -> {
            if (!Files.isRegularFile(p)) {
                return "";
            }
            try (ZipFile zip = new ZipFile(p.toFile())) {
                ZipEntry manifest = zip.getEntry("manifest.json");
                String declared = "";
                if (manifest != null) {
                    try (InputStream in = zip.getInputStream(manifest)) {
                        declared = new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getString("version", "");
                    }
                }
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
                return declared + "+" + HexFormat.of().formatHex(digest).substring(0, 12);
            } catch (IOException | RuntimeException | NoSuchAlgorithmException e) {
                log.debug("Could not read the version of {}: {}", p, e.getMessage());
                return "";
            }
        });
        return version.isEmpty() ? null : version;
    }
}
