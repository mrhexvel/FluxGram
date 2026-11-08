# Material Symbols

The vector paths in `../res-material-symbols/drawable` are derived from Material Symbols Rounded
in `google/material-design-icons` at commit `10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`.

Source: https://github.com/google/material-design-icons

License: Apache License 2.0. A complete copy is already distributed in this source tree at
`TMessagesProj_Modules/media/LICENSE`.

| FluxGram resource | Upstream path |
| --- | --- |
| `fluxgram_material_add_24.xml` | `symbols/android/add/materialsymbolsrounded/add_24px.xml` |
| `fluxgram_material_content_copy_24.xml` | `symbols/android/content_copy/materialsymbolsrounded/content_copy_24px.xml` |
| `fluxgram_material_delete_24.xml` | `symbols/android/delete/materialsymbolsrounded/delete_24px.xml` |
| `fluxgram_material_edit_24.xml` | `symbols/android/edit/materialsymbolsrounded/edit_24px.xml` |
| `fluxgram_material_forward_24.xml` | `symbols/android/forward/materialsymbolsrounded/forward_24px.xml` |
| `fluxgram_material_search_24.xml` | `symbols/android/search/materialsymbolsrounded/search_24px.xml` |
| `fluxgram_material_settings_24.xml` | `symbols/android/settings/materialsymbolsrounded/settings_24px.xml` |
| `fluxgram_material_share_24.xml` | `symbols/android/share/materialsymbolsrounded/share_24px.xml` |
| `fluxgram_material_chat_24.xml` | `symbols/android/chat/materialsymbolsrounded/chat_24px.xml` |
| `fluxgram_material_chat_filled_24.xml` | `symbols/android/chat/materialsymbolsrounded/chat_fill1_24px.xml` |
| `fluxgram_material_edit_square_24.xml` | `symbols/android/edit_square/materialsymbolsrounded/edit_square_24px.xml` |

FluxGram renamed the resources, removed `android:tint="?attr/colorControlNormal"`, normalized XML
formatting, and removed redundant zero-length path commands. The icon runtime renders the vectors
as white masks so each Telegram surface can continue applying its existing color policy.
