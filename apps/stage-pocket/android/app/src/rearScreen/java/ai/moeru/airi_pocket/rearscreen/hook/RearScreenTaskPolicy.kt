package ai.moeru.airi_pocket.rearscreen.hook

/** Enforces the package and source-display boundary before system_server moves a task. */
internal object RearScreenTaskPolicy {
    fun requireValidSource(
        packageName: String,
        currentDisplayId: Int,
        expectedDisplayId: Int?,
    ) {
        if (packageName != AIRI_PACKAGE) {
            throw SecurityException("任务不属于 AIRI")
        }
        if (expectedDisplayId != null && currentDisplayId != expectedDisplayId) {
            throw IllegalStateException("AIRI 不在预期显示器上")
        }
    }

    private const val AIRI_PACKAGE = "ai.moeru.airi_pocket"
}
