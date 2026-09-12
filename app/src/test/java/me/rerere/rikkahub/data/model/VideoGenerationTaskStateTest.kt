package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.VideoGenerationTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGenerationTaskStateTest {
    @Test
    fun `normal asynchronous lifecycle is allowed`() {
        assertTrue(VideoGenerationTaskState.CREATED.canTransitionTo(VideoGenerationTaskState.SUBMITTING))
        assertTrue(VideoGenerationTaskState.SUBMITTING.canTransitionTo(VideoGenerationTaskState.QUEUED))
        assertTrue(VideoGenerationTaskState.QUEUED.canTransitionTo(VideoGenerationTaskState.RUNNING))
        assertTrue(VideoGenerationTaskState.RUNNING.canTransitionTo(VideoGenerationTaskState.DOWNLOADING))
        assertTrue(VideoGenerationTaskState.DOWNLOADING.canTransitionTo(VideoGenerationTaskState.SUCCEEDED))
        assertTrue(VideoGenerationTaskState.SUCCEEDED.isTerminal)
        assertFalse(VideoGenerationTaskState.SUCCEEDED.isRecoverable)
    }

    @Test
    fun `terminal results cannot silently become running`() {
        assertFalse(VideoGenerationTaskState.SUCCEEDED.canTransitionTo(VideoGenerationTaskState.RUNNING))
        assertFalse(VideoGenerationTaskState.EXPIRED.canTransitionTo(VideoGenerationTaskState.RUNNING))
        assertTrue(VideoGenerationTaskState.FAILED.isTerminal)
        assertTrue(VideoGenerationTaskState.FAILED.canTransitionTo(VideoGenerationTaskState.SUBMITTING))
    }

    @Test
    fun `remote success enters download state before local success`() {
        assertEquals(
            VideoGenerationTaskState.DOWNLOADING,
            VideoGenerationTaskStatus.SUCCEEDED.toLocalState(),
        )
    }

    @Test
    fun `cancellation races can converge to the remote state`() {
        assertTrue(
            VideoGenerationTaskState.SUBMITTING.canTransitionTo(
                VideoGenerationTaskState.CANCELING,
            ),
        )
        assertTrue(
            VideoGenerationTaskState.CANCELING.canTransitionTo(
                VideoGenerationTaskState.DOWNLOADING,
            ),
        )
        assertTrue(
            VideoGenerationTaskState.CANCELING.canTransitionTo(
                VideoGenerationTaskState.EXPIRED,
            ),
        )
    }
}
