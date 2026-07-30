package ai.moeru.airi_pocket.rearscreen.hook

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import ai.moeru.airi_pocket.rearscreen.RearScreenContract
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Result of a validated AIRI task transition. */
internal data class RearScreenControllerResult(
    val displayId: Int,
    val message: String,
)

/**
 * Owns AIRI's task location, Xiaomi rear-launcher handoff and rear-display wake lifecycle.
 *
 * The caller supplies AIRI's task id, but this controller never trusts it on its own: every command
 * re-reads the system task snapshot and validates its package and source display before proceeding.
 */
@SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi", "PrivateApi")
internal class RearScreenController(
    private val systemContext: Context,
) {
    private val activityManager = requireNotNull(
        systemContext.getSystemService(ActivityManager::class.java),
    ) { "系统 ActivityManager 不可用" }
    private val displayManager = requireNotNull(
        systemContext.getSystemService(DisplayManager::class.java),
    ) { "系统 DisplayManager 不可用" }
    private val keepAwake = RearDisplayKeepAwake(systemContext)
    private var activeTaskId: Int? = null

    fun getState(taskId: Int): RearScreenControllerResult {
        val task = requireAiriTask(taskId)
        return RearScreenControllerResult(
            displayId = task.displayId,
            message = if (task.displayId == RearScreenContract.REAR_DISPLAY_ID) {
                "AIRI 当前位于背屏"
            } else {
                "AIRI 当前位于主屏"
            },
        )
    }

    fun moveToRear(taskId: Int): RearScreenControllerResult {
        requireRearDisplay()
        val task = requireAiriTask(taskId, RearScreenContract.MAIN_DISPLAY_ID)

        keepAwake.start(task.taskId)
        try {
            stopRearLauncher(task.userId)
            moveTask(task.taskId, RearScreenContract.REAR_DISPLAY_ID)
            activeTaskId = task.taskId
        } catch (error: Throwable) {
            keepAwake.stop()
            throw error
        }

        return RearScreenControllerResult(
            displayId = RearScreenContract.REAR_DISPLAY_ID,
            message = "AIRI 已切换到背屏",
        )
    }

    fun moveToMain(taskId: Int): RearScreenControllerResult {
        val task = requireAiriTask(taskId, RearScreenContract.REAR_DISPLAY_ID)
        val trackedTaskId = activeTaskId
        if (trackedTaskId != null && trackedTaskId != task.taskId) {
            throw IllegalStateException("当前背屏会话属于另一个 AIRI 任务")
        }

        // Keep the rear display awake until the task has safely reached the main display.
        moveTask(task.taskId, RearScreenContract.MAIN_DISPLAY_ID)
        keepAwake.stop()
        activeTaskId = null

        val restoreError = runCatching { restoreRearLauncher() }.exceptionOrNull()
        if (restoreError != null) {
            Log.w(TAG, "AIRI 已返回主屏，但恢复小米背屏桌面失败", unwrap(restoreError))
        }

        return RearScreenControllerResult(
            displayId = RearScreenContract.MAIN_DISPLAY_ID,
            message = if (restoreError == null) {
                "AIRI 已返回主屏"
            } else {
                "AIRI 已返回主屏，但小米背屏桌面恢复失败"
            },
        )
    }

    private fun requireRearDisplay() {
        val display = displayManager.getDisplay(RearScreenContract.REAR_DISPLAY_ID)
        if (display == null || !display.isValid) {
            throw IllegalStateException("未找到小米背屏 Display 1")
        }
    }

    @Suppress("DEPRECATION")
    private fun requireAiriTask(taskId: Int, expectedDisplayId: Int? = null): TaskSnapshot {
        if (taskId < 0) {
            throw IllegalArgumentException("AIRI taskId 无效")
        }

        val task = try {
            activityManager.getRunningTasks(MAX_TASKS)
                .firstOrNull { it.taskId == taskId }
                ?: throw IllegalStateException("没有找到 AIRI 任务")
        } catch (error: Throwable) {
            throw IllegalStateException("读取系统任务失败", unwrap(error))
        }
        val packageName = (task.topActivity ?: task.baseActivity)?.packageName
            ?: throw IllegalStateException("AIRI 任务缺少 Activity 信息")
        val displayId = readTaskInt(task, "displayId", -1)
        RearScreenTaskPolicy.requireValidSource(packageName, displayId, expectedDisplayId)

        return TaskSnapshot(
            taskId = task.taskId,
            userId = readTaskInt(task, "userId", 0),
            displayId = displayId,
        )
    }

    private fun stopRearLauncher(userId: Int) {
        try {
            val managerClass = Class.forName("android.app.ActivityManager")
            val getService = managerClass.getDeclaredMethod("getService").apply {
                isAccessible = true
            }
            val service = checkNotNull(getService.invoke(null)) {
                "系统 ActivityManager 服务不可用"
            }
            findMethod(service.javaClass, "forceStopPackage", 2)
                .invoke(service, REAR_LAUNCHER, userId)
        } catch (error: Throwable) {
            // Moving AIRI can still succeed if HyperOS no longer exposes this hidden method.
            Log.w(TAG, "停止小米背屏桌面失败", unwrap(error))
        }
    }

    private fun restoreRearLauncher() {
        requireRearDisplay()
        val launchIntent = systemContext.packageManager.getLaunchIntentForPackage(REAR_LAUNCHER)
            ?: throw IllegalStateException("小米背屏桌面没有可启动 Activity")
        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(RearScreenContract.REAR_DISPLAY_ID)
        systemContext.startActivity(launchIntent, options.toBundle())
    }

    private fun moveTask(taskId: Int, displayId: Int) {
        try {
            val service = activityTaskManagerService()
            val result = findMethod(service.javaClass, "moveRootTaskToDisplay", 2)
                .invoke(service, taskId, displayId)
            if (result is Boolean && !result) {
                throw IllegalStateException("系统拒绝移动 AIRI 任务")
            }
        } catch (error: Throwable) {
            throw IllegalStateException("移动 AIRI 任务失败", unwrap(error))
        }
    }

    private data class TaskSnapshot(
        val taskId: Int,
        val userId: Int,
        val displayId: Int,
    )

    companion object {
        private const val TAG = "AiriRearScreen"
        private const val REAR_LAUNCHER = "com.xiaomi.subscreencenter"
        const val MAX_TASKS = 64

        private fun activityTaskManagerService(): Any {
            val managerClass = Class.forName("android.app.ActivityTaskManager")
            return checkNotNull(managerClass.getDeclaredMethod("getService").apply {
                isAccessible = true
            }.invoke(null)) { "系统 ActivityTaskManager 服务不可用" }
        }

        private fun findMethod(type: Class<*>, name: String, parameterCount: Int): Method =
            type.methods.firstOrNull {
                it.name == name && it.parameterCount == parameterCount
            }?.apply {
                isAccessible = true
            } ?: throw NoSuchMethodException(name)

        fun readTaskInt(instance: Any, name: String, fallback: Int): Int {
            var type: Class<*>? = instance.javaClass
            while (type != null) {
                try {
                    return type.getDeclaredField(name).apply {
                        isAccessible = true
                    }.getInt(instance)
                } catch (_: NoSuchFieldException) {
                    type = type.superclass
                } catch (_: IllegalAccessException) {
                    return fallback
                }
            }
            return fallback
        }

        private fun unwrap(error: Throwable): Throwable {
            var current = error
            while (current is InvocationTargetException && current.cause != null) {
                current = current.cause!!
            }
            return current
        }
    }
}
