package ai.moeru.airi_pocket.rearscreen

/**
 * Defines the permission-protected protocol shared by AIRI and its system_server module entry.
 *
 * The AIRI task id is the correlation key for every request. The receiver must verify that the
 * task still belongs to AIRI and is on the expected source display before changing system state.
 */
object RearScreenContract {
    const val PERMISSION_CONTROL = "ai.moeru.airi_pocket.permission.REAR_SCREEN_CONTROL"
    const val SYSTEM_PACKAGE = "android"

    const val ACTION_GET_STATE = "ai.moeru.airi_pocket.action.GET_REAR_SCREEN_STATE"
    const val ACTION_MOVE_TO_REAR = "ai.moeru.airi_pocket.action.MOVE_TO_REAR_SCREEN"
    const val ACTION_MOVE_TO_MAIN = "ai.moeru.airi_pocket.action.MOVE_TO_MAIN_SCREEN"

    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_DISPLAY_ID = "displayId"

    const val RESULT_UNAVAILABLE = 0
    const val RESULT_OK = 1
    const val RESULT_ERROR = 2

    const val MAIN_DISPLAY_ID = 0
    const val REAR_DISPLAY_ID = 1
}
