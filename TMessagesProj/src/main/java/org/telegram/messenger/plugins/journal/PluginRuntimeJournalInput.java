package org.telegram.messenger.plugins.journal;

public final class PluginRuntimeJournalInput {
    public final long runtimeInstanceId;
    public final String pluginId;
    public final String archiveSha256;
    public final long generation;
    public final PluginRuntimeJournalCatalog.Kind kind;
    public final PluginRuntimeJournalEvent.SubjectToken subjectToken;
    public final int value;

    public PluginRuntimeJournalInput(
            long runtimeInstanceId,
            String pluginId,
            String archiveSha256,
            long generation,
            PluginRuntimeJournalCatalog.Kind kind,
            PluginRuntimeJournalEvent.SubjectToken subjectToken,
            int value) {
        new PluginRuntimeJournalEvent(
                1,
                0,
                1,
                runtimeInstanceId,
                pluginId,
                archiveSha256,
                generation,
                kind,
                subjectToken,
                value);

        this.runtimeInstanceId = runtimeInstanceId;
        this.pluginId = pluginId;
        this.archiveSha256 = archiveSha256;
        this.generation = generation;
        this.kind = kind;
        this.subjectToken = subjectToken;
        this.value = value;
    }

    PluginRuntimeJournalEvent event(
            long sequence,
            long monotonicTimeMillis,
            long processInstanceId) {
        return new PluginRuntimeJournalEvent(
                sequence,
                monotonicTimeMillis,
                processInstanceId,
                runtimeInstanceId,
                pluginId,
                archiveSha256,
                generation,
                kind,
                subjectToken,
                value);
    }
}
