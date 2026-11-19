package me.rerere.rikkahub.data.knowledge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.rerere.rikkahub.data.db.entity.KnowledgeDocumentEntity
import me.rerere.rikkahub.data.repository.KnowledgeBaseRepository

private val knowledgeImportSlots = Semaphore(2)

class KnowledgeImportWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: KnowledgeBaseRepository,
    private val importer: KnowledgeDocumentImporter,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = knowledgeImportSlots.withPermit {
        val id = inputData.getString(KEY_DOCUMENT_ID) ?: return@withPermit Result.failure()
        if (!repository.transitionImport(id, ACTIVE_STATUSES, KnowledgeDocumentEntity.STATUS_INDEXING)) {
            return@withPermit Result.success()
        }
        try {
            importer.importDocument(id, inputData.getString(KEY_MODEL_ID)?.takeIf { it.isNotBlank() }) { done, total ->
                setProgress(workDataOf(KEY_DOCUMENT_ID to id, KEY_COMPLETED to done, KEY_TOTAL to total))
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            repository.transitionImport(id, ACTIVE_STATUSES, KnowledgeDocumentEntity.STATUS_FAILED,
                error.message?.take(500) ?: error.javaClass.simpleName)
            Result.failure()
        }
    }

    companion object {
        const val KEY_DOCUMENT_ID = "document_id"
        const val KEY_MODEL_ID = "embedding_model_id"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        val ACTIVE_STATUSES = listOf(KnowledgeDocumentEntity.STATUS_QUEUED, KnowledgeDocumentEntity.STATUS_INDEXING)
    }
}
