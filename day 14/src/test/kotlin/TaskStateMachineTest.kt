import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskStateMachineTest {

    @Test
    fun `forward path planning execution validation done`() {
        val t = TaskStateMachine()
        assertEquals(TaskStage.PLANNING, t.stage)
        assertTrue(t.next() is TransitionResult.Moved); assertEquals(TaskStage.EXECUTION, t.stage)
        assertTrue(t.next() is TransitionResult.Moved); assertEquals(TaskStage.VALIDATION, t.stage)
        assertTrue(t.next() is TransitionResult.Moved); assertEquals(TaskStage.DONE, t.stage)
        assertTrue(t.next() is TransitionResult.Rejected)
        assertEquals(TaskStage.DONE, t.stage)
    }

    @Test
    fun `back transitions are limited and validated`() {
        val t = TaskStateMachine()
        t.moveTo(TaskStage.EXECUTION)
        assertTrue(t.back() is TransitionResult.Moved); assertEquals(TaskStage.PLANNING, t.stage)
        assertTrue(t.back() is TransitionResult.Rejected); assertEquals(TaskStage.PLANNING, t.stage)
    }

    @Test
    fun `illegal jump is rejected and state unchanged`() {
        val t = TaskStateMachine()
        val r = t.moveTo(TaskStage.DONE)
        assertTrue(r is TransitionResult.Rejected)
        assertEquals(setOf(TaskStage.EXECUTION), (r as TransitionResult.Rejected).allowed)
        assertEquals(TaskStage.PLANNING, t.stage)
    }

    @Test
    fun `allowed transition table is exactly as specified`() {
        assertEquals(setOf(TaskStage.EXECUTION), TaskStage.allowedFrom(TaskStage.PLANNING))
        assertEquals(setOf(TaskStage.VALIDATION, TaskStage.PLANNING), TaskStage.allowedFrom(TaskStage.EXECUTION))
        assertEquals(setOf(TaskStage.DONE, TaskStage.EXECUTION), TaskStage.allowedFrom(TaskStage.VALIDATION))
        assertTrue(TaskStage.allowedFrom(TaskStage.DONE).isEmpty())
    }

    @Test
    fun `artifacts accumulate per stage`() {
        val t = TaskStateMachine()
        t.start("Сервис авторизации")
        t.putArtifact(TaskStage.PLANNING, "план")
        t.moveTo(TaskStage.EXECUTION)
        t.putArtifact(TaskStage.EXECUTION, "код")
        assertEquals("план", t.artifact(TaskStage.PLANNING))
        assertEquals("код", t.artifact(TaskStage.EXECUTION))
        assertNull(t.artifact(TaskStage.VALIDATION))
    }

    @Test
    fun `snapshot and restore preserve stage and artifacts (pause and resume)`() {
        val t = TaskStateMachine()
        t.start("Сервис авторизации")
        t.putArtifact(TaskStage.PLANNING, "план из 3 шагов")
        t.moveTo(TaskStage.EXECUTION)
        t.putArtifact(TaskStage.EXECUTION, "fun main() {}")

        val restored = TaskStateMachine().apply { restore(t.snapshot()) }
        assertEquals("Сервис авторизации", restored.request)
        assertEquals(TaskStage.EXECUTION, restored.stage)
        assertEquals("план из 3 шагов", restored.artifact(TaskStage.PLANNING))
        assertEquals("fun main() {}", restored.artifact(TaskStage.EXECUTION))
    }
}
