package org.telegram.messenger.plugins.risk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PluginRiskDiff {
    public final String previousArchiveSha256;
    public final String currentArchiveSha256;
    public final List<PluginRiskReport.ModuleDigest> addedModules;
    public final List<PluginRiskReport.ModuleDigest> removedModules;
    public final List<String> changedModuleDigests;
    public final List<PluginRiskEvidence> addedEvidence;
    public final List<PluginRiskEvidence> removedEvidence;
    public final List<String> addedLimitations;
    public final List<String> removedLimitations;
    public final boolean packageSchemaChanged;
    public final boolean analyzerVersionChanged;
    public final boolean hostFingerprintChanged;

    private PluginRiskDiff(
            String previousArchiveSha256,
            String currentArchiveSha256,
            List<PluginRiskReport.ModuleDigest> addedModules,
            List<PluginRiskReport.ModuleDigest> removedModules,
            List<String> changedModuleDigests,
            List<PluginRiskEvidence> addedEvidence,
            List<PluginRiskEvidence> removedEvidence,
            List<String> addedLimitations,
            List<String> removedLimitations,
            boolean packageSchemaChanged,
            boolean analyzerVersionChanged,
            boolean hostFingerprintChanged) {
        this.previousArchiveSha256 = previousArchiveSha256;
        this.currentArchiveSha256 = currentArchiveSha256;
        this.addedModules = Collections.unmodifiableList(addedModules);
        this.removedModules = Collections.unmodifiableList(removedModules);
        this.changedModuleDigests = Collections.unmodifiableList(changedModuleDigests);
        this.addedEvidence = Collections.unmodifiableList(addedEvidence);
        this.removedEvidence = Collections.unmodifiableList(removedEvidence);
        this.addedLimitations = Collections.unmodifiableList(addedLimitations);
        this.removedLimitations = Collections.unmodifiableList(removedLimitations);
        this.packageSchemaChanged = packageSchemaChanged;
        this.analyzerVersionChanged = analyzerVersionChanged;
        this.hostFingerprintChanged = hostFingerprintChanged;
    }

    public static PluginRiskDiff between(
            PluginRiskReport previous,
            PluginRiskReport current) {
        if (previous == null || current == null) {
            throw new IllegalArgumentException("Both risk reports are required");
        }
        if (!previous.pluginId.equals(current.pluginId)) {
            throw new IllegalArgumentException("Risk report plugin IDs do not match");
        }

        ArrayList<PluginRiskReport.ModuleDigest> addedModules = moduleDifference(
                current.modules,
                previous.modules);
        ArrayList<PluginRiskReport.ModuleDigest> removedModules = moduleDifference(
                previous.modules,
                current.modules);
        ArrayList<String> changedModuleDigests = changedModuleDigests(
                previous.modules,
                current.modules);
        ArrayList<PluginRiskEvidence> addedEvidence = difference(
                current.evidence,
                previous.evidence);
        ArrayList<PluginRiskEvidence> removedEvidence = difference(
                previous.evidence,
                current.evidence);
        ArrayList<String> addedLimitations = differenceStrings(
                current.limitations,
                previous.limitations);
        ArrayList<String> removedLimitations = differenceStrings(
                previous.limitations,
                current.limitations);

        return new PluginRiskDiff(
                previous.archiveSha256,
                current.archiveSha256,
                addedModules,
                removedModules,
                changedModuleDigests,
                addedEvidence,
                removedEvidence,
                addedLimitations,
                removedLimitations,
                previous.packageSchemaVersion != current.packageSchemaVersion,
                previous.analyzerVersion != current.analyzerVersion,
                !previous.hostFingerprint.equals(current.hostFingerprint));
    }

    public boolean isEmpty() {
        return previousArchiveSha256.equals(currentArchiveSha256)
                && addedModules.isEmpty()
                && removedModules.isEmpty()
                && changedModuleDigests.isEmpty()
                && addedEvidence.isEmpty()
                && removedEvidence.isEmpty()
                && addedLimitations.isEmpty()
                && removedLimitations.isEmpty()
                && !packageSchemaChanged
                && !analyzerVersionChanged
                && !hostFingerprintChanged;
    }

    private static <T> ArrayList<T> difference(List<T> first, List<T> second) {
        Set<T> excluded = new LinkedHashSet<>(second);
        ArrayList<T> result = new ArrayList<>();

        for (T item : first) {
            if (!excluded.contains(item)) {
                result.add(item);
            }
        }

        return result;
    }

    private static ArrayList<PluginRiskReport.ModuleDigest> moduleDifference(
            List<PluginRiskReport.ModuleDigest> first,
            List<PluginRiskReport.ModuleDigest> second) {
        LinkedHashSet<String> excludedRoles = new LinkedHashSet<>();

        for (PluginRiskReport.ModuleDigest module : second) {
            excludedRoles.add(module.role);
        }

        ArrayList<PluginRiskReport.ModuleDigest> result = new ArrayList<>();

        for (PluginRiskReport.ModuleDigest module : first) {
            if (!excludedRoles.contains(module.role)) {
                result.add(module);
            }
        }

        return result;
    }

    private static ArrayList<String> changedModuleDigests(
            List<PluginRiskReport.ModuleDigest> previous,
            List<PluginRiskReport.ModuleDigest> current) {
        ArrayList<String> result = new ArrayList<>();

        for (PluginRiskReport.ModuleDigest currentModule : current) {
            for (PluginRiskReport.ModuleDigest previousModule : previous) {
                if (currentModule.role.equals(previousModule.role)
                        && !currentModule.sha256.equals(previousModule.sha256)) {
                    result.add(currentModule.role);
                    break;
                }
            }
        }

        return result;
    }

    private static ArrayList<String> differenceStrings(
            List<String> first,
            List<String> second) {
        Set<String> excluded = new LinkedHashSet<>(second);
        ArrayList<String> result = new ArrayList<>();

        for (String item : first) {
            if (!excluded.contains(item)) {
                result.add(item);
            }
        }

        return result;
    }
}
