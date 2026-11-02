package org.telegram.messenger.plugins.journal;

public final class PluginRuntimeJournalCatalog {
    public enum Category {
        RUNTIME(1),
        RESOURCE(2),
        EXTENSION(3),
        MANAGED_HOOK(4),
        MANAGED_TASK(5),
        TYPED_SUBSCRIPTION(6),
        MANAGED_STORAGE(7),
        MANAGED_UI(8),
        QUARANTINE(9),
        CRASH(10),
        CLEANUP(11);

        public final int code;

        Category(int code) {
            this.code = code;
        }
    }

    public enum ValueType {
        NONE,
        COUNT,
        DURATION_BUCKET
    }

    public enum Kind {
        RUNTIME_GENERATION_STARTED(1, Category.RUNTIME, false, ValueType.NONE),
        RUNTIME_GENERATION_RUNNING(2, Category.RUNTIME, false, ValueType.NONE),
        RUNTIME_GENERATION_STOPPED(3, Category.RUNTIME, false, ValueType.NONE),
        RUNTIME_GENERATION_FAILED(4, Category.RUNTIME, false, ValueType.NONE),
        RUNTIME_MIGRATION_STARTED(5, Category.RUNTIME, false, ValueType.NONE),
        RUNTIME_MIGRATION_COMPLETED(6, Category.RUNTIME, false, ValueType.NONE),
        RUNTIME_MIGRATION_FAILED(7, Category.RUNTIME, false, ValueType.NONE),

        RESOURCE_REGISTERED(20, Category.RESOURCE, true, ValueType.NONE),
        RESOURCE_CLOSED(21, Category.RESOURCE, true, ValueType.NONE),
        RESOURCE_CLEANUP_FAILED(22, Category.RESOURCE, true, ValueType.NONE),
        RESOURCE_LIMIT_REJECTED(23, Category.RESOURCE, false, ValueType.COUNT),

        EXTENSION_REGISTRATION_ACCEPTED(40, Category.EXTENSION, true, ValueType.NONE),
        EXTENSION_REGISTRATION_CLOSED(41, Category.EXTENSION, true, ValueType.NONE),
        EXTENSION_BINDING_OPENED(42, Category.EXTENSION, true, ValueType.NONE),
        EXTENSION_BINDING_CLOSED(43, Category.EXTENSION, true, ValueType.NONE),
        EXTENSION_FACTORY_FAILED(44, Category.EXTENSION, true, ValueType.NONE),
        EXTENSION_CALLBACK_FAILED(45, Category.EXTENSION, true, ValueType.NONE),
        EXTENSION_BUDGET_OVERRUN(46, Category.EXTENSION, true, ValueType.DURATION_BUCKET),
        EXTENSION_CLEANUP_FAILED(47, Category.EXTENSION, true, ValueType.NONE),

        MANAGED_HOOK_REGISTERED(60, Category.MANAGED_HOOK, true, ValueType.NONE),
        MANAGED_HOOK_CLOSED(61, Category.MANAGED_HOOK, true, ValueType.NONE),
        MANAGED_HOOK_CALLBACK_FAILED(62, Category.MANAGED_HOOK, true, ValueType.NONE),
        MANAGED_HOOK_BUDGET_OVERRUN(63, Category.MANAGED_HOOK, true, ValueType.DURATION_BUCKET),
        MANAGED_HOOK_CLEANUP_FAILED(64, Category.MANAGED_HOOK, true, ValueType.NONE),

        MANAGED_TASK_SCHEDULED(80, Category.MANAGED_TASK, true, ValueType.NONE),
        MANAGED_TASK_CANCELLED(81, Category.MANAGED_TASK, true, ValueType.NONE),
        MANAGED_TASK_COMPLETED(82, Category.MANAGED_TASK, true, ValueType.NONE),
        MANAGED_TASK_CALLBACK_FAILED(83, Category.MANAGED_TASK, true, ValueType.NONE),
        MANAGED_TASK_QUEUE_REJECTED(84, Category.MANAGED_TASK, false, ValueType.COUNT),
        MANAGED_TASK_LIMIT_REJECTED(85, Category.MANAGED_TASK, false, ValueType.COUNT),

        TYPED_SUBSCRIPTION_ACTIVATED(100, Category.TYPED_SUBSCRIPTION, true, ValueType.NONE),
        TYPED_SUBSCRIPTION_DEACTIVATED(101, Category.TYPED_SUBSCRIPTION, true, ValueType.NONE),
        TYPED_SUBSCRIPTION_DELIVERY_DROPPED(102, Category.TYPED_SUBSCRIPTION, true, ValueType.COUNT),
        TYPED_SUBSCRIPTION_CALLBACK_FAILED(103, Category.TYPED_SUBSCRIPTION, true, ValueType.NONE),

        MANAGED_STORAGE_OPERATION_COMPLETED(120, Category.MANAGED_STORAGE, false, ValueType.COUNT),
        MANAGED_STORAGE_OPERATION_REJECTED(121, Category.MANAGED_STORAGE, false, ValueType.COUNT),
        MANAGED_STORAGE_OPERATION_FAILED(122, Category.MANAGED_STORAGE, false, ValueType.COUNT),

        MANAGED_UI_REGISTERED(140, Category.MANAGED_UI, true, ValueType.NONE),
        MANAGED_UI_BINDING_OPENED(141, Category.MANAGED_UI, true, ValueType.NONE),
        MANAGED_UI_BINDING_CLOSED(142, Category.MANAGED_UI, true, ValueType.NONE),
        MANAGED_UI_FACTORY_FAILED(143, Category.MANAGED_UI, true, ValueType.NONE),
        MANAGED_UI_CLEANUP_FAILED(144, Category.MANAGED_UI, true, ValueType.NONE),

        QUARANTINE_BINDING(160, Category.QUARANTINE, true, ValueType.NONE),
        QUARANTINE_REGISTRATION(161, Category.QUARANTINE, true, ValueType.NONE),
        QUARANTINE_BUDGET(162, Category.QUARANTINE, true, ValueType.DURATION_BUCKET),

        CRASH_ATTRIBUTION_CONFIRMED(180, Category.CRASH, false, ValueType.NONE),
        CRASH_RECOVERY_LATCHED(181, Category.CRASH, false, ValueType.NONE),

        CLEANUP_UNLOAD_SUCCEEDED(200, Category.CLEANUP, false, ValueType.NONE),
        CLEANUP_UNLOAD_FAILED(201, Category.CLEANUP, false, ValueType.NONE),
        CLEANUP_REFERENCES_PENDING(202, Category.CLEANUP, false, ValueType.COUNT);

        public final int code;
        public final Category category;
        public final boolean requiresSubject;
        public final ValueType valueType;

        Kind(int code, Category category, boolean requiresSubject, ValueType valueType) {
            this.code = code;
            this.category = category;
            this.requiresSubject = requiresSubject;
            this.valueType = valueType;
        }
    }

    private PluginRuntimeJournalCatalog() {
    }

    public static Kind kind(int code) {
        for (Kind kind : Kind.values()) {
            if (kind.code == code) {
                return kind;
            }
        }

        return null;
    }
}
