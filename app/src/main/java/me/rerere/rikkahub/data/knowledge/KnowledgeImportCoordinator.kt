package me.rerere.rikkahub.data.knowledge

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.resolveEmbeddingModel
import me.rerere.rikkahub.data.db.entity.KnowledgeBaseEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeDocumentEntity
import me.rerere.rikkahub.data.repository.KnowledgeBaseRepository
import kotlin.uuid.Uuid

class KnowledgeImportCoordinator(
    context: Context,
    private val repository: KnowledgeBaseRepository,
    private val importer: KnowledgeDocumentImporter,
    private val settingsStore: SettingsStore,
) {
    private val workManager = WorkManager.getInstance(context)
    private val schedulingMutex = Mutex()
    val progress = workManager.getWorkInfosByTagFlow(WORK_TAG).map { tasks ->
        tasks.filter { !it.state.isFinished }.mapNotNull { task ->
            val id = task.progress.getString(KnowledgeImportWorker.KEY_DOCUMENT_ID) ?: return@mapNotNull null
            id to (task.progress.getInt(KnowledgeImportWorker.KEY_COMPLETED, 0) to
                task.progress.getInt(KnowledgeImportWorker.KEY_TOTAL, 0))
        }.toMap()
    }

    suspend fun importDocument(base: KnowledgeBaseEntity, uri: Uri) = withContext(Dispatchers.IO) {
        schedulingMutex.withLock {
            val document = importer.prepareDocument(base, uri)
            enqueue(document, base)
        }
    }

    private suspend fun enqueue(document: KnowledgeDocumentEntity, base: KnowledgeBaseEntity) {
        try {
            val settings = settingsStore.settingsFlowRaw.first()
            val model = base.embeddingModelId?.let { settings.resolveEmbeddingModel(Uuid.parse(it)) }
                ?: settings.resolveEmbeddingModel()
            val request = OneTimeWorkRequestBuilder<KnowledgeImportWorker>()
                .setInputData(workDataOf(
                    KnowledgeImportWorker.KEY_DOCUMENT_ID to document.id,
                    KnowledgeImportWorker.KEY_MODEL_ID to model?.id?.toString().orEmpty(),
                ))
                .addTag(WORK_TAG)
                .build()
            workManager.enqueueUniqueWork(workName(document.id), ExistingWorkPolicy.KEEP, request).result.get()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            repository.transitionImport(document.id, KnowledgeImportWorker.ACTIVE_STATUSES,
                KnowledgeDocumentEntity.STATUS_FAILED, error.message?.take(500))
            throw error
        }
    }

    suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        schedulingMutex.withLock {
            val document = repository.getDocument(id) ?: return@withContext
            val base = repository.getBases(setOf(document.knowledgeBaseId)).firstOrNull() ?: return@withContext
            if (workManager.getWorkInfosForUniqueWork(workName(id)).get().any { !it.state.isFinished }) return@withContext
            require(importer.sourceFile(id).isFile) { "导入源文件已不可用，请删除此记录后重新选择文件" }
            if (repository.transitionImport(
                    id,
                    listOf(KnowledgeDocumentEntity.STATUS_FAILED, KnowledgeDocumentEntity.STATUS_CANCELLED),
                    KnowledgeDocumentEntity.STATUS_QUEUED,
                )) enqueue(document, base)
        }
    }

    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        repository.transitionImport(id, KnowledgeImportWorker.ACTIVE_STATUSES, KnowledgeDocumentEntity.STATUS_CANCELLED)
        workManager.cancelUniqueWork(workName(id)).result.get()
    }

    suspend fun deleteDocument(id: String) = withContext(Dispatchers.IO) {
        cancel(id)
        repository.deleteDocument(id)
        importer.discardSource(id)
    }

    suspend fun deleteBase(id: String) = withContext(Dispatchers.IO) {
        repository.getDocuments(id).forEach { deleteDocument(it.id) }
        repository.deleteBase(id)
    }

    /** WorkManager resumes persisted work itself; expose interrupted enqueue/legacy imports for retry. */
    suspend fun recoverOrphanedImports() = withContext(Dispatchers.IO) {
        schedulingMutex.withLock {
            repository.getPendingImports().forEach { document ->
                if (workManager.getWorkInfosForUniqueWork(workName(document.id)).get().none { !it.state.isFinished }) {
                    repository.transitionImport(document.id, KnowledgeImportWorker.ACTIVE_STATUSES,
                        KnowledgeDocumentEntity.STATUS_FAILED, "导入已中断，可重试；若源文件已失效，请重新选择文件")
                }
            }
        }
    }

    companion object {
        private const val WORK_TAG = "knowledge-import"
        private fun workName(id: String) = "$WORK_TAG:$id"
    }
}
