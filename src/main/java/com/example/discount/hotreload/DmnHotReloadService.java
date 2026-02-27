package com.example.discount.hotreload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.kie.api.io.Resource;
import org.kie.dmn.api.core.DMNContext;
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

@Service
public class DmnHotReloadService {

    private static final Logger log = LoggerFactory.getLogger(DmnHotReloadService.class);
    private static final String DMN_CLASSPATH_RESOURCE = "/PromotionCompatibility.dmn";
    private static final String DMN_NAMESPACE = "https://telenor.se/promotion-compatibility";
    private static final String DMN_MODEL_NAME = "PromotionCompatibility";

    @Value("${dmn.editor.storage-path:#{systemProperties['user.dir'] + '/dmn-storage'}}")
    private String storagePath;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile DMNRuntime dmnRuntime;
    private volatile String currentDmnXml;

    @PostConstruct
    public void init() throws IOException {
        Path storageDir = Path.of(storagePath);
        Files.createDirectories(storageDir);

        Path dmnFile = storageDir.resolve("PromotionCompatibility.dmn");
        Path backupFile = storageDir.resolve("PromotionCompatibility.dmn.backup");

        if (Files.exists(dmnFile)) {
            log.info("Loading DMN from storage: {}", dmnFile);
            currentDmnXml = Files.readString(dmnFile, StandardCharsets.UTF_8);
        } else {
            log.info("No stored DMN found, loading from classpath");
            try (InputStream is = getClass().getResourceAsStream(DMN_CLASSPATH_RESOURCE)) {
                if (is == null) {
                    throw new IllegalStateException("DMN resource not found on classpath: " + DMN_CLASSPATH_RESOURCE);
                }
                currentDmnXml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            Files.writeString(dmnFile, currentDmnXml, StandardCharsets.UTF_8);
        }

        if (!Files.exists(backupFile)) {
            Files.writeString(backupFile, currentDmnXml, StandardCharsets.UTF_8);
        }

        dmnRuntime = buildDmnRuntime(currentDmnXml);
        log.info("DMN engine initialized successfully");
    }

    /**
     * Evaluates the DMN model using the current (possibly hot-reloaded) runtime.
     */
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

    public String getBackupDmnXml() throws IOException {
        Path backupFile = Path.of(storagePath).resolve("PromotionCompatibility.dmn.backup");
        if (Files.exists(backupFile)) {
            return Files.readString(backupFile, StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * Updates the DMN model: validates, saves backup, writes new file, and hot-reloads the engine.
     */
    public void updateDmn(String newDmnXml) throws IOException {
        // Validate first (outside the write lock to avoid blocking reads during validation)
        DMNRuntime newRuntime = buildDmnRuntime(newDmnXml);

        lock.writeLock().lock();
        try {
            Path storageDir = Path.of(storagePath);
            Path dmnFile = storageDir.resolve("PromotionCompatibility.dmn");
            Path backupFile = storageDir.resolve("PromotionCompatibility.dmn.backup");

            // Save backup of current version
            if (Files.exists(dmnFile)) {
                Files.copy(dmnFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            }

            Files.writeString(dmnFile, newDmnXml, StandardCharsets.UTF_8);

            this.currentDmnXml = newDmnXml;
            this.dmnRuntime = newRuntime;

            log.info("DMN model updated and hot-reloaded successfully");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Rolls back to the previous backup version.
     */
    public void rollback() throws IOException {
        String backupXml = getBackupDmnXml();
        if (backupXml == null) {
            throw new IllegalStateException("No backup available for rollback");
        }

        DMNRuntime restoredRuntime = buildDmnRuntime(backupXml);

        lock.writeLock().lock();
        try {
            Path dmnFile = Path.of(storagePath).resolve("PromotionCompatibility.dmn");
            Files.writeString(dmnFile, backupXml, StandardCharsets.UTF_8);

            this.currentDmnXml = backupXml;
            this.dmnRuntime = restoredRuntime;

            log.info("DMN model rolled back to previous version");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Builds a DMNRuntime from DMN XML using Drools 10's DMNRuntimeBuilder API.
     */
    private DMNRuntime buildDmnRuntime(String dmnXml) {
        try {
            Resource resource = ResourceFactory.newByteArrayResource(
                    dmnXml.getBytes(StandardCharsets.UTF_8));

            return DMNRuntimeBuilder.fromDefaults()
                    .buildConfiguration()
                    .fromResources(List.of(resource))
                    .getOrElseThrow(ex -> new DmnValidationException(
                            "DMN validation failed: " + ex.getMessage(), ex));
        } catch (DmnValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new DmnValidationException("Invalid DMN XML: " + e.getMessage(), e);
        }
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
