import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskStateMachineTest {

    // Каждый этап даёт артефакт, без которого следующий не начать (см. предусловие в moveTo).
    private fun TaskStateMachine.advanceWithArtifact(): TransitionResult {
        putArtifact(stage, "артефакт ${stage.id}")
        return next()
    }

    @Test
    fun `forward path clarify planning execution validation done`() {
        val t = TaskStateMachine()
        t.start("задача")
        assertEquals(TaskStage.CLARIFY, t.stage)
        assertTrue(t.advanceWithArtifact() is TransitionResult.Moved); assertEquals(TaskStage.PLANNING, t.stage)
        assertTrue(t.advanceWithArtifact() is TransitionResult.Moved); assertEquals(TaskStage.EXECUTION, t.stage)
        assertTrue(t.advanceWithArtifact() is TransitionResult.Moved); assertEquals(TaskStage.VALIDATION, t.stage)
        assertTrue(t.advanceWithArtifact() is TransitionResult.Moved); assertEquals(TaskStage.DONE, t.stage)
        assertTrue(t.next() is TransitionResult.Rejected)
        assertEquals(TaskStage.DONE, t.stage)
    }

    @Test
    fun `back transitions are limited and validated`() {
        val t = TaskStateMachine()
        t.start("задача")
        t.putArtifact(TaskStage.CLARIFY, "требования")
        t.moveTo(TaskStage.PLANNING)
        assertTrue(t.back() is TransitionResult.Moved); assertEquals(TaskStage.CLARIFY, t.stage)
        assertTrue(t.back() is TransitionResult.Rejected); assertEquals(TaskStage.CLARIFY, t.stage)
    }

    @Test
    fun `illegal jump is rejected and state unchanged`() {
        val t = TaskStateMachine()
        t.start("задача")
        val r = t.moveTo(TaskStage.DONE) // из clarify сразу в done — нельзя
        assertTrue(r is TransitionResult.Rejected)
        assertEquals(setOf(TaskStage.PLANNING), (r as TransitionResult.Rejected).allowed)
        assertEquals(TaskStage.CLARIFY, t.stage)
    }

    @Test
    fun `allowed transition table is exactly as specified`() {
        assertEquals(setOf(TaskStage.PLANNING), TaskStage.allowedFrom(TaskStage.CLARIFY))
        assertEquals(setOf(TaskStage.EXECUTION, TaskStage.CLARIFY), TaskStage.allowedFrom(TaskStage.PLANNING))
        assertEquals(setOf(TaskStage.VALIDATION, TaskStage.PLANNING), TaskStage.allowedFrom(TaskStage.EXECUTION))
        assertEquals(setOf(TaskStage.DONE, TaskStage.EXECUTION), TaskStage.allowedFrom(TaskStage.VALIDATION))
        assertTrue(TaskStage.allowedFrom(TaskStage.DONE).isEmpty())
    }

    @Test
    fun `cannot enter a stage without the predecessor artifact (no skipping)`() {
        val t = TaskStateMachine()
        t.start("задача") // на clarify, требований ещё нет
        val r = t.moveTo(TaskStage.PLANNING) // переход разрешён таблицей, но требований нет
        assertTrue(r is TransitionResult.Rejected)
        assertEquals(TaskStage.CLARIFY, t.stage)
    }

    @Test
    fun `re-running an earlier stage invalidates downstream artifacts`() {
        val t = TaskStateMachine()
        t.start("задача")
        t.putArtifact(TaskStage.CLARIFY, "требования")
        t.moveTo(TaskStage.PLANNING); t.putArtifact(TaskStage.PLANNING, "план v1")
        t.moveTo(TaskStage.EXECUTION); t.putArtifact(TaskStage.EXECUTION, "код")
        t.moveTo(TaskStage.VALIDATION); t.putArtifact(TaskStage.VALIDATION, "PASS")
        // Возврат и повторное планирование — код и вердикт должны стать недействительны.
        t.moveTo(TaskStage.EXECUTION); t.moveTo(TaskStage.PLANNING)
        t.putArtifact(TaskStage.PLANNING, "план v2")
        assertNull(t.artifact(TaskStage.EXECUTION))
        assertNull(t.artifact(TaskStage.VALIDATION))
        assertEquals("требования", t.artifact(TaskStage.CLARIFY)) // требования выше по цепочке — целы
        assertTrue(t.moveTo(TaskStage.EXECUTION) is TransitionResult.Moved)
    }

    @Test
    fun `artifacts accumulate per stage`() {
        val t = TaskStateMachine()
        t.start("Сервис авторизации")
        t.putArtifact(TaskStage.CLARIFY, "требования")
        t.moveTo(TaskStage.PLANNING); t.putArtifact(TaskStage.PLANNING, "план")
        t.moveTo(TaskStage.EXECUTION); t.putArtifact(TaskStage.EXECUTION, "код")
        assertEquals("план", t.artifact(TaskStage.PLANNING))
        assertEquals("код", t.artifact(TaskStage.EXECUTION))
        assertNull(t.artifact(TaskStage.VALIDATION))
    }

    @Test
    fun `snapshot and restore preserve stage and artifacts (pause and resume)`() {
        val t = TaskStateMachine()
        t.start("Сервис авторизации")
        t.putArtifact(TaskStage.CLARIFY, "требования")
        t.moveTo(TaskStage.PLANNING); t.putArtifact(TaskStage.PLANNING, "план из 3 шагов")
        t.moveTo(TaskStage.EXECUTION); t.putArtifact(TaskStage.EXECUTION, "fun main() {}")

        val restored = TaskStateMachine().apply { restore(t.snapshot()) }
        assertEquals("Сервис авторизации", restored.request)
        assertEquals(TaskStage.EXECUTION, restored.stage)
        assertEquals("план из 3 шагов", restored.artifact(TaskStage.PLANNING))
        assertEquals("fun main() {}", restored.artifact(TaskStage.EXECUTION))
    }
}
