package com.luum.michi.app.settings.ui.model

/**
 * One page inside the full-screen Settings overlay. [Main] is the index;
 * Accounts, About and Mirror keep their own screens in Root, while the rest are
 * content pages rendered by SettingsScreen.
 */
internal enum class SettingsPage {
    Main,
    General,
    Appearance,
    MyLists,
    Notifications,
    WhatsNew,
    Accounts,
    About,
    Mirror,
}
