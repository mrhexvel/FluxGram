package org.telegram.messenger.fluxgram.icons;

import org.telegram.messenger.fluxgram.MaterialStylePolicy;
import org.telegram.messenger.fluxgram.MaterialVisualStyleRuntime;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class MaterialSymbolsCatalog {
    static final String VERSION = "10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7";
    private static final String SOURCE_PREFIX = "material-symbols\n";
    private static final Map<String, Entry> ENTRIES = entries();

    private MaterialSymbolsCatalog() {
    }

    static boolean isEnabled() {
        return MaterialVisualStyleRuntime.getSnapshot()
                .isEnabled(MaterialStylePolicy.Module.MATERIAL_SYMBOLS);
    }

    static String targetResourceName(String resourceName) {
        Entry entry = ENTRIES.get(resourceName);

        return entry == null ? null : entry.targetResourceName;
    }

    static boolean isAutoMirrored(String resourceName) {
        Entry entry = ENTRIES.get(resourceName);

        return entry != null && entry.autoMirrored;
    }

    static String source(
            String resourceName,
            int targetId,
            int densityDpi,
            int width,
            int height) {
        return SOURCE_PREFIX
                + VERSION + "\n"
                + resourceName + "\n"
                + targetId + "\n"
                + densityDpi + "\n"
                + width + "\n"
                + height;
    }

    private static Map<String, Entry> entries() {
        HashMap<String, Entry> entries = new HashMap<>();
        entries.put("msg_add", new Entry("fluxgram_material_add_24", false));
        entries.put("msg_copy", new Entry("fluxgram_material_content_copy_24", true));
        entries.put("msg_delete", new Entry("fluxgram_material_delete_24", false));
        entries.put("msg_edit", new Entry("fluxgram_material_edit_24", false));
        entries.put("msg_forward", new Entry("fluxgram_material_forward_24", true));
        entries.put("msg_search", new Entry("fluxgram_material_search_24", false));
        entries.put("msg_settings", new Entry("fluxgram_material_settings_24", false));
        entries.put("msg_share", new Entry("fluxgram_material_share_24", false));
        entries.put("tabs_chats_24", new Entry("fluxgram_material_chat_24", true));
        entries.put("tabs_chats_active_24", new Entry("fluxgram_material_chat_filled_24", true));
        entries.put("fab_compose_small", new Entry("fluxgram_material_edit_square_24", false));

        return Collections.unmodifiableMap(entries);
    }

    private static final class Entry {
        private final String targetResourceName;
        private final boolean autoMirrored;

        private Entry(String targetResourceName, boolean autoMirrored) {
            this.targetResourceName = targetResourceName;
            this.autoMirrored = autoMirrored;
        }
    }
}
