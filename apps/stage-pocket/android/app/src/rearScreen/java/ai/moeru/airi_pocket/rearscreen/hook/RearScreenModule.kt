package ai.moeru.airi_pocket.rearscreen.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.moeru.airi_pocket.rearscreen.RearScreenContract
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Installs AIRI's permission-protected display controller in system_server.
 *
 * Call stack:
 *
 * onSystemServerStarting
 *   -> installBridge
 *     -> ControlReceiver.onReceive
 *       -> RearScreenController
 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class RearScreenModule : XposedModule() {
    private var receiver: BroadcastReceiver? = null

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            log(Log.WARN, TAG, "背屏模块要求 Android 13 或更高版本")
            return
        }

        try {
            val systemServerClass = Class.forName(
                "com.android.server.SystemServer",
                false,
                param.classLoader,
            )
            val method = systemServerClass.declaredMethods.singleOrNull {
                it.name == "startOtherServices" && it.parameterCount == 1
            }?.apply {
                isAccessible = true
            } ?: throw NoSuchMethodException("SystemServer.startOtherServices(*)")
            hook(method).setId("airi-rear-screen-register-bridge").intercept { chain ->
                val result = chain.proceed()
                installBridge(chain.thisObject)
                result
            }
        } catch (error: Throwable) {
            log(Log.ERROR, TAG, "安装 system_server hook 失败", error)
        }
    }

    private fun installBridge(systemServer: Any) {
        if (receiver != null) {
            return
        }

        try {
            val context = findSystemContext(systemServer)
            val bridge = ControlReceiver(RearScreenController(context))
            context.registerReceiver(
                bridge,
                IntentFilter().apply {
                    addAction(RearScreenContract.ACTION_GET_STATE)
                    addAction(RearScreenContract.ACTION_MOVE_TO_REAR)
                    addAction(RearScreenContract.ACTION_MOVE_TO_MAIN)
                },
                RearScreenContract.PERMISSION_CONTROL,
                Handler(Looper.getMainLooper()),
                Context.RECEIVER_EXPORTED,
            )
            receiver = bridge
            log(Log.INFO, TAG, "AIRI 背屏控制入口已在 system_server 注册")
        } catch (error: Throwable) {
            log(Log.ERROR, TAG, "注册 AIRI 背屏控制入口失败", error)
        }
    }

    private fun findSystemContext(systemServer: Any): Context {
        val value = systemServer.javaClass.getDeclaredField("mSystemContext").apply {
            isAccessible = true
        }.get(systemServer)
        return value as? Context
            ?: throw IllegalStateException("SystemServer.mSystemContext 不可用")
    }

    private class ControlReceiver(
        private val controller: RearScreenController,
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val taskId = intent.getIntExtra(RearScreenContract.EXTRA_TASK_ID, -1)
            val identity = Binder.clearCallingIdentity()
            try {
                val result = when (intent.action) {
                    RearScreenContract.ACTION_GET_STATE -> controller.getState(taskId)
                    RearScreenContract.ACTION_MOVE_TO_REAR -> controller.moveToRear(taskId)
                    RearScreenContract.ACTION_MOVE_TO_MAIN -> controller.moveToMain(taskId)
                    else -> throw IllegalArgumentException("未知背屏控制命令")
                }
                resultData = result.message
                setResultExtras(
                    Bundle().apply {
                        putInt(RearScreenContract.EXTRA_DISPLAY_ID, result.displayId)
                    },
                )
                resultCode = RearScreenContract.RESULT_OK
            } catch (error: Throwable) {
                resultCode = RearScreenContract.RESULT_ERROR
                resultData = "system_server 操作失败：${conciseMessage(error)}"
                Log.e(TAG, "处理 AIRI 背屏命令失败", error)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        private fun conciseMessage(error: Throwable): String {
            var cause = error
            while (cause.cause != null) {
                cause = cause.cause!!
            }
            return cause.message?.takeUnless(String::isBlank)
                ?: cause.javaClass.simpleName
        }
    }

    private companion object {
        const val TAG = "AiriRearScreen"
    }
}
