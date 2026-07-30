package ai.moeru.airi_pocket.rearscreen

import android.content.Context
import ai.moeru.airi_pocket.BuildConfig
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/** Exposes AIRI's two-display transition as a small, typed Capacitor boundary. */
@CapacitorPlugin(name = "RearScreen")
class RearScreenPlugin : Plugin() {
    @PluginMethod
    fun getState(call: PluginCall) {
        if (!BuildConfig.REAR_SCREEN_MODULE_ENABLED) {
            call.resolve(state(supported = false, available = false, localDisplayId()))
            return
        }

        val activity = activity
        if (activity == null) {
            call.reject("AIRI Activity 不可用")
            return
        }

        RearScreenClient.getState(activity, activity.taskId) { response ->
            call.resolve(
                state(
                    supported = true,
                    available = response.success,
                    displayId = response.displayId,
                    message = response.message,
                ),
            )
        }
    }

    @PluginMethod
    fun moveToRear(call: PluginCall) {
        move(call, RearScreenClient::moveToRear)
    }

    @PluginMethod
    fun moveToMain(call: PluginCall) {
        move(call, RearScreenClient::moveToMain)
    }

    private fun move(
        call: PluginCall,
        operation: (Context, Int, (RearScreenResponse) -> Unit) -> Unit,
    ) {
        if (!BuildConfig.REAR_SCREEN_MODULE_ENABLED) {
            call.reject("当前 AIRI 构建不包含背屏模块")
            return
        }

        val activity = activity
        if (activity == null) {
            call.reject("AIRI Activity 不可用")
            return
        }

        operation(activity, activity.taskId) { response ->
            if (!response.success) {
                call.reject(response.message)
                return@operation
            }

            call.resolve(
                state(
                    supported = true,
                    available = true,
                    displayId = response.displayId,
                    message = response.message,
                ),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun localDisplayId(): Int =
        activity?.windowManager?.defaultDisplay?.displayId ?: RearScreenContract.MAIN_DISPLAY_ID

    private fun state(
        supported: Boolean,
        available: Boolean,
        displayId: Int,
        message: String? = null,
    ) = JSObject().apply {
        put("supported", supported)
        put("available", available)
        put(
            "display",
            if (displayId == RearScreenContract.REAR_DISPLAY_ID) "rear" else "main",
        )
        message?.let { put("message", it) }
    }
}
