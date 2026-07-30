package ai.moeru.airi_pocket.rearscreen.hook

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import ai.moeru.airi_pocket.rearscreen.RearScreenContract
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Keeps the Xiaomi rear display awake only while the correlated AIRI task remains there. */
@SuppressLint("BlockedPrivateApi")
internal class RearDisplayKeepAwake(systemContext: Context) {
    private val activityManager = requireNotNull(
        systemContext.getSystemService(ActivityManager::class.java),
    ) { "系统 ActivityManager 不可用" }
    private val inputManager = requireNotNull(
        systemContext.getSystemService(InputManager::class.java),
    ) { "系统 InputManager 不可用" }
    private val setDisplayId: Method by lazy {
        InputEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }
    private val injectInputEvent: Method by lazy {
        inputManager.javaClass.methods.firstOrNull {
            it.name == "injectInputEvent" &&
                it.parameterCount == 2 &&
                InputEvent::class.java.isAssignableFrom(it.parameterTypes[0])
        }?.apply { isAccessible = true }
            ?: throw NoSuchMethodException("InputManager.injectInputEvent")
    }

    private var wakeLoop: ScheduledExecutorService? = null
    private var taskId = -1
    private var wakeCount = 0

    @Synchronized
    fun start(movedTaskId: Int) {
        stop()
        sendWakeUp()
        taskId = movedTaskId
        wakeCount = 0
        wakeLoop = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "AiriRearDisplayWakeLoop").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay(
                { keepAwake(movedTaskId) },
                WAKE_INTERVAL_MILLIS,
                WAKE_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    @Synchronized
    fun stop() {
        taskId = -1
        wakeLoop?.shutdownNow()
        wakeLoop = null
    }

    private fun keepAwake(checkedTaskId: Int) {
        try {
            sendWakeUp()
            wakeCount++
            if (wakeCount >= TASK_CHECK_WAKE_COUNT) {
                wakeCount = 0
                if (!isTaskOnRearDisplay(checkedTaskId)) {
                    stopIfCurrent(checkedTaskId)
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "背屏唤醒失败，停止 AIRI 保活", error)
            stopIfCurrent(checkedTaskId)
        }
    }

    private fun sendWakeUp() {
        val eventTime = SystemClock.uptimeMillis()
        injectKeyEvent(KeyEvent.ACTION_DOWN, eventTime)
        injectKeyEvent(KeyEvent.ACTION_UP, eventTime)
    }

    private fun injectKeyEvent(action: Int, eventTime: Long) {
        val event = KeyEvent(
            eventTime,
            eventTime,
            action,
            KeyEvent.KEYCODE_WAKEUP,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_KEYBOARD,
        )
        setDisplayId.invoke(event, RearScreenContract.REAR_DISPLAY_ID)
        injectInputEvent.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
    }

    @Synchronized
    private fun stopIfCurrent(checkedTaskId: Int) {
        if (taskId == checkedTaskId) {
            stop()
        }
    }

    @Suppress("DEPRECATION")
    private fun isTaskOnRearDisplay(checkedTaskId: Int): Boolean {
        val task = activityManager.getRunningTasks(RearScreenController.MAX_TASKS)
            .firstOrNull { it.taskId == checkedTaskId }
            ?: return false
        return RearScreenController.readTaskInt(task, "displayId", -1) ==
            RearScreenContract.REAR_DISPLAY_ID
    }

    private companion object {
        const val TAG = "AiriRearScreen"
        const val WAKE_INTERVAL_MILLIS = 100L
        const val TASK_CHECK_WAKE_COUNT = 20
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    }
}
