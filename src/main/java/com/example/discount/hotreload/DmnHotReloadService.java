package com.example.discount.hotreload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

import com.example.discount.rules.RuleTables;

import org.kie.api.io.Resource;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNMessage;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.internal.io.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * The one DMN runtime. Rules are edited in the browser, validated, versioned and swapped in
 * without a redeploy &mdash; which is the reason this service exists at all.
 */
@Service
public class DmnHotReloadService {

    private static final Logger log = LoggerFactory.getLogger(DmnHotReloadService.class);
    private static final String DMN_CLASSPATH_RESOURCE = "/PromotionCompatibility.dmn";
    private static final String DMN_NAMESPACE = "https://telenor.se/promotion-compatibility";
    private static final String DMN_MODEL_NAME = "PromotionCompatibility";
    private static final String DMN_FILE = "PromotionCompatibility.dmn";
    private static final String VERSIONS_DIR = "versions";
    private static final int VERSIONS_KEPT = 20;

    @Value("${dmn.editor.storage-path:#{systemProperties['user.dir'] + '/dmn-storage'}}")
    private String storagePath;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile DMNRuntime dmnRuntime;
    private volatile String currentDmnXml;
    private volatile RuleTables ruleTables;

    @PostConstruct
    public void init() throws IOException {
        Path storageDir = Path.of(storagePath);
        Files.createDirectories(storageDir);
        Files.createDirectories(storageDir.resolve(VERSIONS_DIR));

        Path dmnFile = storageDir.resolve(DMN_FILE);
        String seed = readClasspathDmn();

        if (Files.exists(dmnFile)) {
            currentDmnXml = Files.readString(dmnFile, StandardCharsets.UTF_8);
            String storedVersion = RuleTables.versionOf(currentDmnXml);
            String seedVersion = RuleTables.versionOf(seed);
            if (!storedVersion.equals(seedVersion)) {
                // The stored copy shadows the packaged one. That is the point of hot reload, but
                // it also means a stale file can silently serve rules nobody expects, so say so.
                log.warn("Serving rules from {} which DIFFER from the packaged model. "
                                + "storage={} classpath={}. Delete the file to reseed from the build.",
                        dmnFile, storedVersion, seedVersion);
            }
        } else {
            log.info("No stored DMN found; seeding {} from the classpath", dmnFile);
            currentDmnXml = seed;
            Files.writeString(dmnFile, currentDmnXml, StandardCharsets.UTF_8);
        }

        dmnRuntime = buildDmnRuntime(currentDmnXml);
        ruleTables = RuleTables.parse(currentDmnXml);
        log.info("DMN engine initialised, rule set {}", ruleTables.version());
    }

    private String readClasspathDmn() throws IOException {
        try (InputStream is = getClass().getResourceAsStream(DMN_CLASSPATH_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException(
                        "DMN resource not found on classpath: " + DMN_CLASSPATH_RESOURCE);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Evaluates every decision in the model against the current runtime. */
    public DMNResult evaluate(Map<String, Object> inputs) {
        lock.readLock().lock();
        try {
            DMNModel model = dmnRuntime.getModel(DMN_NAMESPACE, DMN_MODEL_NAME);
            DMNContext context = dmnRuntime.newContext();
            inputs.forEach(context::set);
            return dmnRuntime.evaluateAll(model, context);
        } finally {
            lock.readLock().unlock();
        }
    }

    public String getCurrentDmnXml() {
        lock.readLock().lock();
        try {
            return currentDmnXml;
        } finally {
            lock.readLock().unlock();
        }
    }

    public RuleTables getRuleTables() {
        lock.readLock().lock();
        try {
            return ruleTables;
        } finally {
            lock.readLock().unlock();
        }
    }

    public String getRuleSetVersion() {
        return getRuleTables().version();
    }

    /** Validates, archives the outgoing version, writes the new one and swaps the engine. */
    public void updateDmn(String newDmnXml) throws IOException {
        DMNRuntime newRuntime = buildDmnRuntime(newDmnXml);
        RuleTables newTables = RuleTables.parse(newDmnXml);

        lock.writeLock().lock();
        try {
            Path storageDir = Path.of(storagePath);
            Path dmnFile = storageDir.resolve(DMN_FILE);

            if (Files.exists(dmnFile)) {
                archive(storageDir, Files.readString(dmnFile, StandardCharsets.UTF_8));
            }
            Files.writeString(dmnFile, newDmnXml, StandardCharsets.UTF_8);

            this.currentDmnXml = newDmnXml;
            this.dmnRuntime = newRuntime;
            this.ruleTables = newTables;
            log.info("Rules updated and hot-reloaded, rule set now {}", newTables.version());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Writes the outgoing model into the version history. One undo was too thin for a surface
     * business users edit directly, so every save is kept, oldest pruned beyond {@value
     * #VERSIONS_KEPT}.
     */
    private void archive(Path storageDir, String outgoing) throws IOException {
        Path versions = storageDir.resolve(VERSIONS_DIR);
        Files.createDirectories(versions);
        String stamp = Instant.now().toString().replace(':', '-').replace('.', '-');
        Files.writeString(versions.resolve("PromotionCompatibility-" + stamp + ".dmn"),
                outgoing, StandardCharsets.UTF_8);

        List<Path> kept = listVersions();
        for (int i = VERSIONS_KEPT; i < kept.size(); i++) {
            Files.deleteIfExists(kept.get(i));
        }
    }

    /** Archived versions, newest first. */
    public List<Path> listVersions() throws IOException {
        Path versions = Path.of(storagePath).resolve(VERSIONS_DIR);
        if (!Files.isDirectory(versions)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(versions)) {
            return new ArrayList<>(files
                    .filter(p -> p.getFileName().toString().endsWith(".dmn"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList());
        }
    }

    public List<String> listVersionIds() throws IOException {
        List<String> ids = new ArrayList<>();
        for (Path p : listVersions()) {
            ids.add(p.getFileName().toString());
        }
        return ids;
    }

    /** The most recently archived model, or null when nothing has been saved yet. */
    public String getBackupDmnXml() throws IOException {
        List<Path> versions = listVersions();
        return versions.isEmpty() ? null : Files.readString(versions.get(0), StandardCharsets.UTF_8);
    }

    /** Restores the most recent archived version. */
    public void rollback() throws IOException {
        List<Path> versions = listVersions();
        if (versions.isEmpty()) {
            throw new IllegalStateException("No previous version available to roll back to");
        }
        rollbackTo(versions.get(0).getFileName().toString());
    }

    /** Restores one named archived version. Restoring is itself a save, so it is also archived. */
    public void rollbackTo(String versionId) throws IOException {
        if (versionId == null || versionId.contains("/") || versionId.contains("\\")
                || versionId.contains("..")) {
            throw new IllegalArgumentException("Invalid version id: " + versionId);
        }
        Path target = Path.of(storagePath).resolve(VERSIONS_DIR).resolve(versionId);
        if (!Files.exists(target)) {
            throw new IllegalStateException("No such version: " + versionId);
        }
        updateDmn(Files.readString(target, StandardCharsets.UTF_8));
        log.info("Rolled back to {}", versionId);
    }

    /**
     * Builds a runtime, and rejects the model if the engine reported anything wrong with it.
     *
     * <p>The error check matters more than it looks: {@code getOrElseThrow} only fires when the
     * build itself blows up. A decision table with, say, a missing input entry builds
     * "successfully" and is then silently discarded at evaluation time &mdash; which is exactly
     * how a rule saved from the editor can disappear while the editor reports success.
     */
    private DMNRuntime buildDmnRuntime(String dmnXml) {
        DMNRuntime runtime;
        try {
            Resource resource = ResourceFactory.newByteArrayResource(
                    dmnXml.getBytes(StandardCharsets.UTF_8));
            runtime = DMNRuntimeBuilder.fromDefaults()
                    .buildConfiguration()
                    .fromResources(List.of(resource))
                    .getOrElseThrow(ex -> new DmnValidationException(
                            "DMN validation failed: " + ex.getMessage(), ex));
        } catch (DmnValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new DmnValidationException("Invalid DMN XML: " + e.getMessage(), e);
        }

        DMNModel model = runtime.getModel(DMN_NAMESPACE, DMN_MODEL_NAME);
        if (model == null) {
            throw new DmnValidationException("The file does not contain the model "
                    + DMN_MODEL_NAME + " in namespace " + DMN_NAMESPACE);
        }
        List<DMNMessage> errors = model.getMessages(DMNMessage.Severity.ERROR);
        if (!errors.isEmpty()) {
            StringBuilder detail = new StringBuilder("The model has ")
                    .append(errors.size()).append(" error(s): ");
            for (DMNMessage m : errors) {
                detail.append(System.lineSeparator()).append("  - ").append(m.getText());
            }
            throw new DmnValidationException(detail.toString());
        }
        return runtime;
    }

    public static class DmnValidationException extends RuntimeException {
        public DmnValidationException(String message) {
            super(message);
        }

        public DmnValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
