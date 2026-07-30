package ai.moeru.airi_pocket.rearscreen.hook

import org.junit.Assert.assertThrows
import org.junit.Test

class RearScreenTaskPolicyTest {
    @Test
    fun `accepts AIRI on the expected source display`() {
        RearScreenTaskPolicy.requireValidSource(
            packageName = "ai.moeru.airi_pocket",
            currentDisplayId = 0,
            expectedDisplayId = 0,
        )
    }

    @Test
    fun `rejects a task owned by another package`() {
        val error = assertThrows(SecurityException::class.java) {
            RearScreenTaskPolicy.requireValidSource(
                packageName = "com.example.other",
                currentDisplayId = 0,
                expectedDisplayId = 0,
            )
        }

        org.junit.Assert.assertEquals("任务不属于 AIRI", error.message)
    }

    @Test
    fun `rejects AIRI on the wrong source display`() {
        val error = assertThrows(IllegalStateException::class.java) {
            RearScreenTaskPolicy.requireValidSource(
                packageName = "ai.moeru.airi_pocket",
                currentDisplayId = 1,
                expectedDisplayId = 0,
            )
        }

        org.junit.Assert.assertEquals("AIRI 不在预期显示器上", error.message)
    }

    @Test
    fun `allows state queries without a source display constraint`() {
        RearScreenTaskPolicy.requireValidSource(
            packageName = "ai.moeru.airi_pocket",
            currentDisplayId = 1,
            expectedDisplayId = null,
        )
    }
}
