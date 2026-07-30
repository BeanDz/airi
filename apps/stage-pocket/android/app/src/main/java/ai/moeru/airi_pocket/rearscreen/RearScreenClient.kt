package ai.moeru.airi_pocket.rearscreen

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Response returned by the system_server side of a rear-screen request. */
data class RearScreenResponse(
    val available: Boolean,
    val success: Boolean,
    val displayId: Int,
    val message: String,
)

/**
 * Sends one-shot rear-screen commands and completes each request exactly once.
 *
 * The timeout also covers a receiver that was registered but became unresponsive during a system
 * transition. Late ordered-broadcast results are ignored after the timeout completes the request.
 */
object RearScreenClient {
    fun getState(context: Context, taskId: Int, onResult: (RearScreenResponse) -> Unit) {
        send(context, RearScreenContract.ACTION_GET_STATE, taskId, onResult)
    }

    fun moveToRear(context: Context, taskId: Int, onResult: (RearScreenResponse) -> Unit) {
        send(context, RearScreenContract.ACTION_MOVE_TO_REAR, taskId, onResult)
    }

    fun moveToMain(context: Context, taskId: Int, onResult: (RearScreenResponse) -> Unit) {
        send(context, RearScreenContract.ACTION_MOVE_TO_MAIN, taskId, onResult)
    }

    private fun send(
        context: Context,
        action: String,
        taskId: Int,
        onResult: (RearScreenResponse) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val completed = AtomicBoolean(false)
        val unavailableMessage = "背屏模块未启用或 system_server 控制入口不可用"
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                onResult(
                    RearScreenResponse(
                        available = false,
                        success = false,
                        displayId = currentDisplayId(context),
                        message = "背屏模块响应超时",
                    ),
                )
            }
        }
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (!completed.compareAndSet(false, true)) {
                    return
                }
                handler.removeCallbacks(timeout)

                val resultDisplayId = getResultExtras(false)
                    ?.getInt(RearScreenContract.EXTRA_DISPLAY_ID, currentDisplayId(context))
                    ?: currentDisplayId(context)
                val message = resultData?.takeUnless(String::isBlank) ?: unavailableMessage
                onResult(
                    RearScreenResponse(
                        available = resultCode != RearScreenContract.RESULT_UNAVAILABLE,
                        success = resultCode == RearScreenContract.RESULT_OK,
                        displayId = resultDisplayId,
                        message = message,
                    ),
                )
            }
        }

        handler.postDelayed(timeout, REQUEST_TIMEOUT_MILLIS)
        context.sendOrderedBroadcast(
            Intent(action)
                .setPackage(RearScreenContract.SYSTEM_PACKAGE)
                .putExtra(RearScreenContract.EXTRA_TASK_ID, taskId),
            null,
            resultReceiver,
            handler,
            RearScreenContract.RESULT_UNAVAILABLE,
            unavailableMessage,
            null,
        )
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayId(context: Context): Int =
        (context as? Activity)?.windowManager?.defaultDisplay?.displayId
            ?: RearScreenContract.MAIN_DISPLAY_ID

    private const val REQUEST_TIMEOUT_MILLIS = 3_000L
}
