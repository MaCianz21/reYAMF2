package com.mja.reyamf.xposed.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import com.mja.reyamf.common.reflect.argTypes
import com.mja.reyamf.common.reflect.args
import com.mja.reyamf.common.reflect.getObject
import com.mja.reyamf.common.reflect.invokeMethod
import com.mja.reyamf.common.reflect.invokeMethodAuto
import com.mja.reyamf.common.reflect.invokeMethodAutoAs
import com.mja.reyamf.common.reflect.loadClass
import com.mja.reyamf.common.reflect.loadClassOrNull
import com.mja.reyamf.common.reflect.newInstance
import com.mja.reyamf.R
import com.mja.reyamf.xposed.services.YAMFManager
import com.mja.reyamf.xposed.utils.log
import com.mja.reyamf.xposed.utils.registerReceiver
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.github.kyuubiran.ezxhelper.xposed.common.HookParam
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import java.lang.reflect.Method

/**
 * Launcher hooks, ported from the legacy XposedBridge implementation to the libxposed API.
 *
 * The launcher is only hooked once the configuration broadcast arrives, exactly like the legacy
 * variant; only the registration mechanism changed.
 */
object HookLauncher {
    const val TAG = "reYAMF_HookLauncher"

    private var isRegistered = false

    fun install(packageName: String, classLoader: ClassLoader) {
        if (loadClassOrNull("com.android.launcher3.Launcher", classLoader) == null) {
            log(TAG, "no Launcher class in $packageName, skipping")
            return
        }
        runCatching {
            val onCreate = loadClass("com.android.launcher3.Launcher", classLoader)
                .methodNamed("onCreate")
                ?: error("Launcher.onCreate not found")
            onCreate.createHook {
                after { param ->
                    // Runs inside the launcher UI: a changed framework field must not crash it.
                    runCatching {
                        attachToLauncher(packageName, classLoader, param.thisObject as Activity)
                    }.onFailure { log(TAG, "cannot attach to the launcher", it) }
                }
            }
        }.onFailure { log(TAG, "cannot hook Launcher.onCreate", it) }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun attachToLauncher(packageName: String, classLoader: ClassLoader, activity: Activity) {
        if (isRegistered) return
        val application = activity.application
        application.registerReceiver(LauncherHooksConfig.ACTION_RECEIVE_LAUNCHER_CONFIG) { _, intent ->
            val hookRecent = intent.getBooleanExtra(LauncherHooksConfig.EXTRA_HOOK_RECENT, false)
            val hookTaskbar = intent.getBooleanExtra(LauncherHooksConfig.EXTRA_HOOK_TASKBAR, false)
            val hookPopup = intent.getBooleanExtra(LauncherHooksConfig.EXTRA_HOOK_POPUP, false)
            val hookTransientTaskbar =
                intent.getBooleanExtra(LauncherHooksConfig.EXTRA_HOOK_TRANSIENT_TASKBAR, false)
            log(
                TAG,
                "receive config for $packageName hookRecent=$hookRecent hookTaskbar=$hookTaskbar " +
                    "hookPopup=$hookPopup hookTranslucentTaskbar=$hookTransientTaskbar"
            )
            if (hookRecent) runCatching { hookRecent(classLoader) }
                .onFailure { log(TAG, "hook recent failed", it) }
            if (hookTaskbar) runCatching { hookTaskbar(classLoader) }
                .onFailure { log(TAG, "hook taskbar failed", it) }
            if (hookPopup) runCatching { hookPopup(classLoader) }
                .onFailure { log(TAG, "hook popup failed", it) }
            if (hookTransientTaskbar) runCatching { hookTransientTaskbar(classLoader) }
                .onFailure { log(TAG, "hook transient failed", it) }
            application.unregisterReceiver(this)
        }
        application.sendBroadcast(Intent(YAMFManager.ACTION_GET_LAUNCHER_CONFIG).apply {
            `package` = "android"
            putExtra("sender", application.packageName)
        })

        isRegistered = true
    }

    private fun hookRecent(classLoader: ClassLoader) {
        log(TAG, "hooking recent")
        val factory = loadClass("com.android.quickstep.TaskOverlayFactory", classLoader)
        factory.declaredMethods
            .filter { it.name == "getEnabledShortcuts" }
            .forEach { method ->
                method.createHook {
                    after { param ->
                        runCatching { addOpenInYamfShortcut(param, classLoader) }
                            .onFailure { log(TAG, "cannot add the open-in-reYAMF shortcut", it) }
                    }
                }
            }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun addOpenInYamfShortcut(param: HookParam, classLoader: ClassLoader) {
        val taskView = param.args.getOrNull(0) as? View ?: return
        val shortcuts = param.result as? MutableList<Any> ?: return
        val itemInfo = shortcuts.firstOrNull()?.getObject("mItemInfo") ?: return

        var task: Any? = null

        runCatching {
            task = taskView.invokeMethod("getTask")
        }.onFailure {
            val taskContainers = taskView.getObject("taskContainers") as List<*>
            task = taskContainers[0]!!.getObject("task")
        }

        val activity = taskView.context
        val key = task!!.getObject("key")!!
        val taskId = key.getObject("id") as Int
        val userId = key.getObject("userId") as Int

        val classRemoteActionShortcut =
            loadClass("com.android.launcher3.popup.RemoteActionShortcut", classLoader)

        val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
            setPackage("android")
        }

        runCatching {
            val itemInfoTmp =
                itemInfo.javaClass.newInstance(args(itemInfo), argTypes(itemInfo.javaClass))
            val topComponent = itemInfoTmp!!.invokeMethod("getTargetComponent") as ComponentName
            intent.putExtra(YAMFManager.EXTRA_COMPONENT_NAME, topComponent)
        }.onFailure {
            val topComponent = extractComponentInfo(itemInfo.toString()).toString()
            intent.putExtra(YAMFManager.EXTRA_COMPONENT_NAME, topComponent)
        }
        intent.putExtra(YAMFManager.EXTRA_TASK_ID, taskId)
        intent.putExtra(YAMFManager.EXTRA_USER_ID, userId)
        intent.putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_RECENT)

        val action = RemoteAction(
            Icon.createWithBitmap(
                EzXposed.moduleRes.getDrawable(R.drawable.ic_picture_in_picture_alt_24, null).toBitmap()
            ),
            EzXposed.moduleRes.getString(R.string.open_with_yamf),
            "",
            PendingIntent.getBroadcast(
                EzXposed.appContext,
                1345,
                intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        val c = classRemoteActionShortcut.constructors[0]
        val shortcut = when (c.parameterCount) {
            4 -> c.newInstance(action, activity, itemInfo, null)
            3 -> c.newInstance(action, activity, itemInfo)
            else -> {
                log(TAG, "unknown RemoteActionShortcut constructor: ${c.toGenericString()}")
                null
            }
        }

        if (shortcut != null) {
            shortcuts.add(shortcut)
        }
    }

    private fun hookTaskbar(classLoader: ClassLoader) {
        log(TAG, "hooking taskbar")
        val taskbar = loadClass("com.android.launcher3.taskbar.TaskbarActivityContext", classLoader)

        taskbar.methodNamedOrNull("startItemInfoActivity")?.createHook {
            replace { param ->
                val infoIntent = param.args[0]!!.invokeMethodAutoAs<Intent>("getIntent")!!
                val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                    setPackage("android")
                    putExtra(YAMFManager.EXTRA_COMPONENT_NAME, infoIntent.component)
                    putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_TASKBAR)
                }
                EzXposed.appContext.sendBroadcast(intent)
                null
            }
        }

        val classWorkspaceiteminfo =
            loadClass("com.android.launcher3.model.data.WorkspaceItemInfo", classLoader)
        taskbar.methodNamed("onTaskbarIconClicked").createHook {
            before { param ->
                val tag = param.args[0]!!.invokeMethodAuto("getTag")!!
                if (classWorkspaceiteminfo.isInstance(tag)) {
                    val infoIntent = tag.invokeMethodAutoAs<Intent>("getIntent")!!
                    val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                        setPackage("android")
                        putExtra(YAMFManager.EXTRA_COMPONENT_NAME, infoIntent.component)
                        putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_TASKBAR)
                    }
                    EzXposed.appContext.sendBroadcast(intent)
                    // Swallow the original click, as the legacy replace hook did.
                    param.result = Unit
                }
            }
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun handleProxyMethod(methodName: String, param: HookParam) {
        val _this = param.thisObject
        when (methodName) {
            "onClick" -> {
                val mItemInfo = _this.getObject("mItemInfo")!!
                val componentName = mItemInfo.invokeMethod("getTargetComponent") as ComponentName
                val userId = (mItemInfo.getObject("user") as UserHandle)
                EzXposed.appContext
                    .sendBroadcast(Intent(YAMFManager.ACTION_OPEN_APP).apply {
                        setPackage("android")
                        putExtra(YAMFManager.EXTRA_COMPONENT_NAME, componentName)
                        putExtra(YAMFManager.EXTRA_USER_ID, userId)
                        putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_POPUP)
                    })
                _this.invokeMethodAuto("dismissTaskMenuView")
                param.result = Unit
            }

            "setIconAndContentDescriptionFor" -> {
                val view = param.args[0] as ImageView
                view.setImageDrawable(
                    EzXposed.moduleRes.getDrawable(
                        R.drawable.ic_picture_in_picture_alt_24,
                        null
                    )
                )
                view.contentDescription = EzXposed.moduleRes.getString(R.string.open_with_yamf)
                param.result = Unit
            }

            "setIconAndLabelFor" -> {
                val iconView = param.args[0] as View
                val labelView = param.args[1] as TextView
                iconView.background =
                    EzXposed.moduleRes.getDrawable(R.drawable.ic_picture_in_picture_alt_24, null)
                labelView.text = EzXposed.moduleRes.getString(R.string.open_with_yamf)
                param.result = Unit
            }
        }
    }

    private fun hookPopup(classLoader: ClassLoader) {
        log(TAG, "hooking popup")
        val install = loadClassOrNull("com.android.launcher3.popup.SystemShortcut\$Install", classLoader)
        if (install == null) {
            log(TAG, "no SystemShortcut\$Install on this ROM, the popup shortcut stays away")
            return
        }

        // The legacy variant replaced the static SystemShortcut.INSTALL factory. Android 17
        // declares it as a static final field and a reflective write to it throws, so instead the
        // Install shortcut's own methods are hooked and gated on the instance type, which is what
        // the factory swap was reaching for.
        val base = loadClassOrNull("com.android.launcher3.popup.SystemShortcut", classLoader)
        val declaredHere = install.declaredMethods.map { it.name to it.parameterCount }.toSet()
        val methods = install.declaredMethods.toList() +
            base?.declaredMethods.orEmpty().filter { (it.name to it.parameterCount) !in declaredHere }

        var hooked = 0
        methods.forEach { method ->
            runCatching {
                method.createHook {
                    before { param ->
                        if (!install.isInstance(param.thisObjectOrNull)) return@before
                        // Runs inside the launcher UI: a changed framework field must not escape.
                        runCatching { handleProxyMethod(method.name, param) }
                            .onFailure { log(TAG, "popup shortcut ${method.name} failed", it) }
                    }
                }
                hooked++
            }.onFailure { log(TAG, "cannot hook popup ${method.name}", it) }
        }
        log(TAG, "popup shortcut hooked on $hooked methods")
    }

    private fun hookTransientTaskbar(classLoader: ClassLoader) {
        log(TAG, "hook transientTaskbar")
        loadClass("com.android.launcher3.util.DisplayController", classLoader)
            .methodNamed("isTransientTaskbar")
            .createHook {
                returnConstant(true)
            }
    }

    fun extractComponentInfo(input: String): String? {
        val regex = Regex("""ComponentInfo\{[^}]+\}""")
        return regex.find(input)?.value
    }

    private fun Class<*>.methodNamed(name: String): Method =
        methodNamedOrNull(name) ?: error("$this.$name not found")

    private fun Class<*>.methodNamedOrNull(name: String): Method? =
        declaredMethods.firstOrNull { it.name == name && !it.isBridge && !it.isSynthetic }
            ?: superclass?.methodNamedOrNull(name)
}
