package org.telegram.messenger.fluxgram.icons;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class IconPackBuiltIns {
    static final String SOLAR_ID = "fluxgram.solar";

    private static final Pack SOLAR = new Pack(
            SOLAR_ID,
            "Solar",
            "@immat0x1",
            "1.0",
            471);
    private static final List<String> IDS = Collections.singletonList(SOLAR_ID);
    private static final Map<String, String> SOLAR_ALIASES = solarAliases();

    private IconPackBuiltIns() {
    }

    static List<String> ids() {
        return IDS;
    }

    static boolean contains(String id) {
        return SOLAR_ID.equals(id);
    }

    static Pack get(String id) {
        return SOLAR_ID.equals(id) ? SOLAR : null;
    }

    static String targetResourceName(String id, String resourceName) {
        if (!SOLAR_ID.equals(id) || !IconPackManifest.isValidResourceName(resourceName)) {
            return null;
        }

        String alias = SOLAR_ALIASES.get(resourceName);

        return alias != null ? alias : resourceName + "_solar";
    }

    private static Map<String, String> solarAliases() {
        HashMap<String, String> aliases = new HashMap<>();
        aliases.put("bot_file", "msg_round_file_solar");
        aliases.put("calls_bluetooth", "calls_menu_bluetooth_solar");
        aliases.put("calls_headphones", "calls_menu_headset_solar");
        aliases.put("calls_mute_mini", "calls_mute_solar");
        aliases.put("calls_speaker", "calls_menu_speaker_solar");
        aliases.put("calls_unmute", "input_mic_pressed_solar");
        aliases.put("calls_video", "profile_video_solar");
        aliases.put("chat_calls_video", "profile_video_solar");
        aliases.put("chat_calls_voice", "profile_phone_solar");
        aliases.put("chats_pin", "msg_pin_solar");
        aliases.put("chats_unpin", "msg_unpin_solar");
        aliases.put("group_edit", "group_edit_profile_solar");
        aliases.put("ic_call", "profile_phone_solar");
        aliases.put("ic_lock_header", "list_secret_solar");
        aliases.put("input_notify_off", "msg_bell_mute_solar");
        aliases.put("input_notify_on", "msg_notifications_solar");
        aliases.put("list_pin", "msg_pin_mini_solar");
        aliases.put("menu_devices", "msg_devices_solar");
        aliases.put("msg2_animations", "msg_played_solar");
        aliases.put("msg2_archived_stickers", "msg_archive_solar");
        aliases.put("msg2_ask_question", "msg_ask_question_solar");
        aliases.put("msg2_autodelete", "msg_autodelete_solar");
        aliases.put("msg2_block2", "msg_block2_solar");
        aliases.put("msg2_call_earpiece", "msg_calls_solar");
        aliases.put("msg2_chats_add", "msg_chats_add_solar");
        aliases.put("msg2_data", "msg_data_solar");
        aliases.put("msg2_devices", "msg_devices_solar");
        aliases.put("msg2_discussion", "msg_discussion_solar");
        aliases.put("msg2_email", "msg_email_solar");
        aliases.put("msg2_folder", "msg_folder_solar");
        aliases.put("msg2_gif", "msg_gif_solar");
        aliases.put("msg2_help", "msg_psa_solar");
        aliases.put("msg2_language", "msg_language_solar");
        aliases.put("msg2_link2", "msg_link2_solar");
        aliases.put("msg2_night_auto", "msg_night_auto_solar");
        aliases.put("msg2_notifications", "msg_notifications_solar");
        aliases.put("msg2_permissions", "msg_permissions_solar");
        aliases.put("msg2_policy", "msg_policy_solar");
        aliases.put("msg2_proxy_off", "proxy_off_solar");
        aliases.put("msg2_proxy_on", "proxy_on_solar");
        aliases.put("msg2_reactions2", "msg_reactions_solar");
        aliases.put("msg2_secret", "msg_secret_solar");
        aliases.put("msg2_smile_status", "input_smile_solar");
        aliases.put("msg2_sticker", "msg_sticker_solar");
        aliases.put("msg2_trending", "msg_trending_solar");
        aliases.put("msg2_videocall", "msg_videocall_solar");
        aliases.put("msg_addcontact", "msg_contact_add_solar");
        aliases.put("msg_archived_stickers", "msg_archive_solar");
        aliases.put("msg_bot", "msg_bots_solar");
        aliases.put("msg_callback", "msg_calls_solar");
        aliases.put("msg_channel_filled", "msg_folders_channels_solar");
        aliases.put("msg_clear_input", "smiles_tab_clear_solar");
        aliases.put("msg_clearcache", "msg_delete_solar");
        aliases.put("msg_customize", "msg_photo_settings_solar");
        aliases.put("msg_discuss", "msg_ask_question_solar");
        aliases.put("msg_emoji_question", "msg_psa_solar");
        aliases.put("msg_emoji_smiles", "input_smile_solar");
        aliases.put("msg_emoji_stickers", "msg_sticker_solar");
        aliases.put("msg_folders", "msg_folder_solar");
        aliases.put("msg_forward", "msg_share_solar");
        aliases.put("msg_groups_create", "groups_create_solar");
        aliases.put("msg_header_share", "msg_share_filled_solar");
        aliases.put("msg_help", "msg_psa_solar");
        aliases.put("msg_invite", "msg_contact_add_solar");
        aliases.put("msg_link", "msg_link2_solar");
        aliases.put("msg_link_folder", "msg_link2_solar");
        aliases.put("msg_location_alert2", "msg_bell_mute_solar");
        aliases.put("msg_msgbubble", "msg_msgbubble3_solar");
        aliases.put("msg_openin", "msg_instant_link_solar");
        aliases.put("msg_palette", "msg_theme_solar");
        aliases.put("msg_panel_forward", "msg_panel_reply_solar");
        aliases.put("msg_payment_address", "msg_location_solar");
        aliases.put("msg_photoeditor", "msg_photo_draw_solar");
        aliases.put("msg_qr_mini", "msg_qrcode_mini_solar");
        aliases.put("msg_reactions2", "msg_reactions_solar");
        aliases.put("msg_report", "msg_report_other_solar");
        aliases.put("msg_requests", "msg_contact_add_solar");
        aliases.put("msg_round_file_s", "msg_round_file_solar");
        aliases.put("msg_settings_old", "msg_settings_solar");
        aliases.put("msg_smile_status", "input_smile_solar");
        aliases.put("msg_topic_close", "msg_remove_solar");
        aliases.put("msg_unmute", "notifications_on_solar");
        aliases.put("msg_usersearch", "msg_user_search_solar");
        aliases.put("msg_view_file", "msg_message_solar");
        aliases.put("msg_viewchats", "msg_discuss_solar");
        aliases.put("msg_voice_speaker", "notifications_on_solar");
        aliases.put("passcode_delete", "smiles_tab_clear_solar");
        aliases.put("picker", "ic_colorpicker_solar");
        aliases.put("pin", "bot_location_solar");
        aliases.put("reactionbutton", "msg_reactions_solar");
        aliases.put("reactionchatslist", "msg_reactions_filled_solar");
        aliases.put("search_files_filled", "msg_round_file_solar");
        aliases.put("select_between", "msg_select_between_solar");
        aliases.put("share", "msg_filled_shareout_solar");
        aliases.put("smiles_tab_gif", "msg_gif_solar");
        aliases.put("smiles_tab_smiles", "input_smile_solar");
        aliases.put("smiles_tab_stickers", "msg_sticker_solar");
        aliases.put("stickers_recent", "msg_emoji_recent_solar");
        aliases.put("video_quality1", "msg_settings_solar");

        return Collections.unmodifiableMap(aliases);
    }

    static final class Pack {
        final String id;
        final String name;
        final String author;
        final String version;
        final int iconCount;

        private Pack(String id, String name, String author, String version, int iconCount) {
            this.id = id;
            this.name = name;
            this.author = author;
            this.version = version;
            this.iconCount = iconCount;
        }
    }
}
