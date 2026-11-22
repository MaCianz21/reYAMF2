package com.mja.reyamf.xposed.hook

/**
 * Launcher-side hook contract shared by both hook flavours: the module service broadcasts the
 * configuration with these extras and both entry points hook the same launcher packages.
 */
object LauncherHooksConfig {
    const val ACTION_RECEIVE_LAUNCHER_CONFIG = "com.mja.reyamf.ACTION_RECEIVE_LAUNCHER_CONFIG"

    const val EXTRA_HOOK_RECENT = "hookRecent"
    const val EXTRA_HOOK_TASKBAR = "hookTaskbar"
    const val EXTRA_HOOK_POPUP = "hookPopup"
    const val EXTRA_HOOK_TRANSIENT_TASKBAR = "hookTransientTaskbar"

    val LAUNCHER_PACKAGES = setOf(
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.motorola.launcher3"
    )
}
