package me.rerere.rikkahub.ui.pages.imggen

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.GeminiImageGenerationOptions
import me.rerere.ai.provider.GeminiSafetySettings
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderRequestException
import me.rerere.ai.provider.ProviderRetryController
import me.rerere.ai.provider.retryProviderRequest
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.common.android.Logging
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.MAX_GENERATION_RETRY_COUNT
import me.rerere.rikkahub.data.ai.MAX_GENERATION_RETRY_DURATION_SECONDS
import me.rerere.rikkahub.data.ai.MAX_GENERATION_RETRY_INTERVAL_SECONDS
import me.rerere.rikkahub.data.ai.MIN_GENERATION_RETRY_COUNT
import me.rerere.rikkahub.data.ai.MIN_GENERATION_RETRY_DURATION_SECONDS
import me.rerere.rikkahub.data.ai.MIN_GENERATION_RETRY_INTERVAL_SECONDS
import me.rerere.rikkahub.data.ai.NetworkRecoveryCoordinator
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.GenMediaFolderEntity
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.service.toDiagnosticMessage
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

@Serializable
data class GeneratedImage(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String,
    val provider: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val format: String? = null,
    val seed: Long? = null,
    val folderId: String? = null,
    val fileSizeBytes: Long = 0L,
    val type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
)

private fun GenMediaEntity.toGeneratedImage(filesManager: FilesManager): GeneratedImage {
    val imagesDir = filesManager.getImagesDir()
    val fullPath = File(imagesDir, this.path.removePrefix("images/")).absolutePath

    return GeneratedImage(
        id = this.id,
        prompt = this.prompt,
        filePath = fullPath,
        timestamp = this.createAt,
        model = this.modelId,
        provider = this.providerName,
        width = this.width,
        height = this.height,
        format = this.format,
        seed = this.seed,
        folderId = this.folderId,
        fileSizeBytes = File(fullPath).length().coerceAtLeast(0L),
        type = this.type,
    )
}

class ImgGenVM(
    context: Application,
    val settingsStore: SettingsStore,
    val providerManager: ProviderManager,
    val genMediaRepository: GenMediaRepository,
    private val filesManager: FilesManager,
) : AndroidViewModel(context) {
    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _numberOfImages = MutableStateFlow(1)
    val numberOfImages: StateFlow<Int> = _numberOfImages

    private val _size = MutableStateFlow(ImageGenSize.AUTO.value)
    val size: StateFlow<String> = _size

    private val _quality = MutableStateFlow<String?>(null)
    val quality: StateFlow<String?> = _quality

    private val _outputFormat = MutableStateFlow<String?>(null)
    val outputFormat: StateFlow<String?> = _outputFormat

    private val _background = MutableStateFlow<String?>(null)
    val background: StateFlow<String?> = _background

    private val _outputCompression = MutableStateFlow(100)
    val outputCompression: StateFlow<Int> = _outputCompression

    private val _resolution = MutableStateFlow<String?>(null)
    val resolution: StateFlow<String?> = _resolution

    private val _thinkingLevel = MutableStateFlow<String?>(null)
    val thinkingLevel: StateFlow<String?> = _thinkingLevel

    private val _geminiImageOptions = MutableStateFlow(GeminiImageGenerationOptions())
    val geminiImageOptions: StateFlow<GeminiImageGenerationOptions> = _geminiImageOptions

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating
    private var cancelJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentGeneratedImages = MutableStateFlow<List<GeneratedImage>>(emptyList())
    val currentGeneratedImages: StateFlow<List<GeneratedImage>> = _currentGeneratedImages

    private val _referenceImages = MutableStateFlow<List<String>>(emptyList())
    val referenceImages: StateFlow<List<String>> = _referenceImages

    private val _galleryQuery = MutableStateFlow("")
    val galleryQuery: StateFlow<String> = _galleryQuery

    private val _selectedGalleryFolderId = MutableStateFlow<String?>(null)
    val selectedGalleryFolderId: StateFlow<String?> = _selectedGalleryFolderId

    private val _isGalleryMutating = MutableStateFlow(false)
    val isGalleryMutating: StateFlow<Boolean> = _isGalleryMutating

    val galleryFolders: StateFlow<List<GenMediaFolderEntity>> = genMediaRepository.getFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    @OptIn(FlowPreview::class)
    val generatedImages: Flow<PagingData<GeneratedImage>> = combine(
        _galleryQuery.debounce(GALLERY_SEARCH_DEBOUNCE_MILLIS).map(String::trim),
        _selectedGalleryFolderId,
    ) { query, folderId -> query to folderId }
        .distinctUntilChanged()
        .flatMapLatest { (query, folderId) ->
            Pager(
                config = PagingConfig(pageSize = 20, enablePlaceholders = false),
                pagingSourceFactory = { genMediaRepository.searchMedia(query, folderId) },
            ).flow
        }
        .map { pagingData ->
            pagingData.map { entity -> entity.toGeneratedImage(filesManager) }
        }
        .cachedIn(viewModelScope)

    fun updatePrompt(prompt: String) {
        _prompt.value = prompt
    }

    fun updateGalleryQuery(query: String) {
        _galleryQuery.value = query
    }

    fun selectGalleryFolder(folderId: String?) {
        _selectedGalleryFolderId.value = folderId
    }

    fun createGalleryFolder(name: String) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) return
        val folder = GenMediaFolderEntity(
            id = Uuid.random().toString(),
            name = normalizedName,
            createAt = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            genMediaRepository.createFolder(folder)
            _selectedGalleryFolderId.value = folder.id
        }
    }

    fun moveImageToFolder(image: GeneratedImage, folderId: String?) {
        moveImagesToFolder(listOf(image), folderId)
    }

    fun moveImagesToFolder(images: Collection<GeneratedImage>, folderId: String?) {
        val ids = images.map(GeneratedImage::id).filter { it > 0 }.distinct()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _isGalleryMutating.value = true
            try {
                genMediaRepository.moveMediaToFolder(ids, folderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gallery image move failed (${e::class.simpleName})")
                _error.value = getApplication<Application>().getString(R.string.imggen_error_move_failed)
            } finally {
                _isGalleryMutating.value = false
            }
        }
    }

    fun importGalleryImages(uris: List<Uri>, folderId: String?) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isGalleryMutating.value = true
            try {
                withContext(Dispatchers.IO) {
                    importGalleryImagesIntoStorage(uris.distinct(), folderId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gallery image import failed (${e::class.simpleName})")
                _error.value = getApplication<Application>().getString(R.string.imggen_error_import_failed)
            } finally {
                _isGalleryMutating.value = false
            }
        }
    }

    private suspend fun importGalleryImagesIntoStorage(uris: List<Uri>, folderId: String?) {
        val context = getApplication<Application>()
        val imagesDirectory = filesManager.getImagesDir().apply { mkdirs() }
        val importedFiles = mutableListOf<File>()
        val entities = mutableListOf<GenMediaEntity>()
        try {
            uris.forEachIndexed { index, uri ->
                currentCoroutineContext().ensureActive()
                val displayName = filesManager.getFileNameFromUri(uri)
                    ?.takeIf(String::isNotBlank)
                    ?: "image-${index + 1}"
                val mimeType = filesManager.getFileMimeType(uri) ?: "application/octet-stream"
                val destination = File(
                    imagesDirectory,
                    FileUtils.buildUuidFileName(displayName = displayName, mimeType = mimeType),
                )
                val stagingFile = File(imagesDirectory, ".${destination.name}.part")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        stagingFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 256 * 1024)
                        }
                    } ?: error("Unable to read selected image")
                    require(stagingFile.length() > 0L) { "Selected image is empty" }
                    val metadata = readStoredImageMetadata(stagingFile, mimeType)
                    require(metadata.width != null && metadata.height != null) {
                        "Selected file is not a supported image"
                    }
                    check(stagingFile.renameTo(destination)) { "Unable to finalize imported image" }
                    importedFiles += destination
                    entities += GenMediaEntity(
                        path = "images/${destination.name}",
                        modelId = LOCAL_IMPORT_MODEL_ID,
                        prompt = displayName,
                        createAt = System.currentTimeMillis() + index,
                        type = GenMediaEntity.TYPE_IMAGE_IMPORT,
                        providerName = null,
                        width = metadata.width,
                        height = metadata.height,
                        format = metadata.format,
                        folderId = folderId,
                    )
                } finally {
                    stagingFile.delete()
                }
            }
            genMediaRepository.insertMedia(entities)
        } catch (error: Throwable) {
            importedFiles.forEach(File::delete)
            throw error
        }
    }

    fun dissolveGalleryFolder(folderId: String) {
        viewModelScope.launch {
            try {
                genMediaRepository.dissolveFolder(folderId)
                clearSelectedGalleryFolder(folderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gallery folder dissolution failed (${e::class.simpleName})")
                _error.value = getApplication<Application>()
                    .getString(R.string.imggen_error_dissolve_folder_failed)
            }
        }
    }

    fun deleteGalleryFolderWithContents(folderId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val files = genMediaRepository.getMediaInFolder(folderId)
                        .map(::resolveStoredImageFile)
                        .distinctBy { it.absolutePath }
                    deleteFilesWithRollback(files) {
                        genMediaRepository.deleteFolderWithContents(folderId)
                    }
                }
                clearSelectedGalleryFolder(folderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gallery folder deletion failed (${e::class.simpleName})")
                _error.value = getApplication<Application>()
                    .getString(R.string.imggen_error_delete_folder_failed)
            }
        }
    }

    private fun clearSelectedGalleryFolder(folderId: String) {
        if (_selectedGalleryFolderId.value == folderId) {
            _selectedGalleryFolderId.value = null
        }
    }

    private fun resolveStoredImageFile(media: GenMediaEntity): File {
        val imagesDirectory = filesManager.getImagesDir().canonicalFile
        val imageFile = File(
            imagesDirectory,
            media.path.removePrefix("images/"),
        ).canonicalFile
        check(imageFile.path.startsWith(imagesDirectory.path + File.separator)) {
            "Stored image path is outside the gallery directory"
        }
        return imageFile
    }

    fun updateNumberOfImages(count: Int) {
        _numberOfImages.value = count.coerceIn(1, 10)
    }

    fun updateSize(size: String) {
        _size.value = size
    }

    fun updateQuality(value: String?) {
        _quality.value = value
    }

    fun updateOutputFormat(value: String?) {
        _outputFormat.value = value
    }

    fun updateBackground(value: String?) {
        _background.value = value
    }

    fun updateOutputCompression(value: Int) {
        _outputCompression.value = value.coerceIn(0, 100)
    }

    fun updateResolution(value: String?) {
        _resolution.value = value
    }

    fun updateThinkingLevel(value: String?) {
        _thinkingLevel.value = value
    }

    fun updateGeminiTextResponse(enabled: Boolean) {
        _geminiImageOptions.value = _geminiImageOptions.value.copy(includeTextResponse = enabled)
    }

    fun updateGeminiWebSearch(enabled: Boolean) {
        _geminiImageOptions.value = _geminiImageOptions.value.copy(webSearchGrounding = enabled)
    }

    fun updateGeminiImageSearch(enabled: Boolean) {
        _geminiImageOptions.value = _geminiImageOptions.value.copy(imageSearchGrounding = enabled)
    }

    fun updateGeminiSafetySettings(settings: GeminiSafetySettings) {
        _geminiImageOptions.value = _geminiImageOptions.value.copy(safetySettings = settings)
    }

    fun addReferenceImages(paths: List<String>) {
        if (_isGenerating.value) {
            deleteReferenceFiles(paths)
            return
        }
        val current = _referenceImages.value
        val accepted = (current + paths).distinct().take(MAX_REFERENCE_IMAGES)
        val rejected = paths.filterNot { it in accepted || it in current }
        _referenceImages.value = accepted
        deleteReferenceFiles(rejected)
    }

    fun removeReferenceImage(path: String) {
        if (_isGenerating.value) return
        _referenceImages.value = _referenceImages.value.filterNot { it == path }
        deleteReferenceFiles(listOf(path))
    }

    fun clearReferenceImages() {
        if (_isGenerating.value) return
        deleteReferenceFiles(_referenceImages.value)
        _referenceImages.value = emptyList()
    }

    fun limitReferenceImages(maxImages: Int) {
        if (_isGenerating.value) return
        val accepted = _referenceImages.value.take(maxImages.coerceAtLeast(0))
        val rejected = _referenceImages.value.drop(accepted.size)
        if (rejected.isEmpty()) return
        _referenceImages.value = accepted
        deleteReferenceFiles(rejected)
    }

    fun clearError() {
        _error.value = null
    }

    fun startNewSession() {
        cancelJob?.cancel()
        clearReferenceImages()
        _prompt.value = ""
        _currentGeneratedImages.value = emptyList()
        _error.value = null
        _isGenerating.value = false
    }

    fun generateImage() {
        if(prompt.value.isBlank()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("No model selected")

                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")
                val providerClient = providerManager.getProviderByType(provider)
                val constraints = providerClient.imageGenerationConstraints(provider, model)
                require(constraints.supportsGeneration) {
                    "Selected provider does not support image generation"
                }

                val requestPrompt = _prompt.value
                val params = ImageGenerationParams(
                    model = model,
                    prompt = requestPrompt,
                    numOfImages = _numberOfImages.value.coerceAtMost(constraints.maxOutputImages),
                    size = _size.value,
                    quality = _quality.value,
                    outputFormat = _outputFormat.value,
                    background = _background.value,
                    outputCompression = _outputCompression.value,
                    resolution = _resolution.value,
                    thinkingLevel = _thinkingLevel.value,
                    geminiOptions = _geminiImageOptions.value,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies
                )

                val images = providerClient.generateImage(provider, params)

                collectImageGenerationWithRetry(
                    settings = settings,
                    images = images,
                    prompt = requestPrompt,
                    modelName = model.displayName,
                    providerName = provider.name,
                    requestedSeed = model.customBodies.requestedSeed(),
                )
            } catch (e: Exception) {
                if(e is CancellationException) return@launch
                Log.e(TAG, "Image generation failed", e)
                val displayMessage = userFacingError(e)
                _error.value = displayMessage
                logImageError(R.string.imggen_error_generation_title, displayMessage, e)
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun editImage() {
        if (prompt.value.isBlank() || referenceImages.value.isEmpty()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("No model selected")

                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")
                val providerClient = providerManager.getProviderByType(provider)
                val constraints = providerClient.imageGenerationConstraints(provider, model)
                require(constraints.supportsEdit) {
                    "Selected provider does not support image editing"
                }

                val requestPrompt = _prompt.value
                val sourceImages = _referenceImages.value.toList().take(constraints.maxReferenceImages)
                val params = ImageEditParams(
                    model = model,
                    prompt = requestPrompt,
                    images = sourceImages,
                    numOfImages = _numberOfImages.value.coerceAtMost(constraints.maxOutputImages),
                    size = _size.value,
                    quality = _quality.value,
                    outputFormat = _outputFormat.value,
                    background = _background.value,
                    outputCompression = _outputCompression.value,
                    resolution = _resolution.value,
                    thinkingLevel = _thinkingLevel.value,
                    geminiOptions = _geminiImageOptions.value,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies
                )

                val images = providerClient.editImage(provider, params)

                collectImageGenerationWithRetry(
                    settings = settings,
                    images = images,
                    prompt = requestPrompt,
                    modelName = model.displayName,
                    providerName = provider.name,
                    requestedSeed = model.customBodies.requestedSeed(),
                    type = GenMediaEntity.TYPE_IMAGE_EDIT,
                    sourcePaths = sourceImages.joinToString("\n"),
                )
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Log.e(TAG, "Image editing failed", e)
                val displayMessage = userFacingError(e)
                _error.value = displayMessage
                logImageError(R.string.imggen_error_edit_title, displayMessage, e)
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
    }

    private suspend fun collectImageGenerationWithRetry(
        settings: Settings,
        images: Flow<ImageGenerationItem>,
        prompt: String,
        modelName: String,
        providerName: String,
        requestedSeed: Long?,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ) {
        val retryController = ProviderRetryController(
            maxRetries = settings.generationRetryMaxRetries.coerceIn(
                MIN_GENERATION_RETRY_COUNT,
                MAX_GENERATION_RETRY_COUNT,
            ),
            initialDelayMillis = settings.generationRetryInitialIntervalSeconds.coerceIn(
                MIN_GENERATION_RETRY_INTERVAL_SECONDS,
                MAX_GENERATION_RETRY_INTERVAL_SECONDS,
            ) * 1_000L,
            maxDurationMillis = settings.generationRetryMaxDurationSeconds.coerceIn(
                MIN_GENERATION_RETRY_DURATION_SECONDS,
                MAX_GENERATION_RETRY_DURATION_SECONDS,
            ) * 1_000L,
        )
        var receivedFinalImage = false
        val networkRecovery = NetworkRecoveryCoordinator(getApplication())
        var attemptNetworkVersion = networkRecovery.snapshot()
        try {
            retryProviderRequest(
                enabled = settings.enableGenerationRetry,
                retryController = retryController,
                canRetry = { !receivedFinalImage },
                shouldRetry = { error ->
                    networkRecovery.shouldRetry(error, attemptNetworkVersion)
                },
                onRetry = { retryNumber, delayMillis ->
                    Log.w(TAG, "Image request retry #$retryNumber in ${delayMillis}ms")
                },
                delayBeforeRetry = { delayMillis ->
                    networkRecovery.awaitNetworkAndBackoff(
                        retryDelayMillis = delayMillis,
                        remainingDurationMillis = retryController.remainingDurationMillis(),
                    )
                },
            ) {
                attemptNetworkVersion = networkRecovery.snapshot()
                collectImageGeneration(
                    images = images,
                    prompt = prompt,
                    modelName = modelName,
                    providerName = providerName,
                    requestedSeed = requestedSeed,
                    type = type,
                    sourcePaths = sourcePaths,
                    onFinalImage = { receivedFinalImage = true },
                )
            }
        } finally {
            networkRecovery.close()
        }
    }

    private suspend fun collectImageGeneration(
        images: Flow<ImageGenerationItem>,
        prompt: String,
        modelName: String,
        providerName: String,
        requestedSeed: Long?,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
        onFinalImage: () -> Unit = {},
    ) {
        val finalImages = mutableListOf<GeneratedImage>()
        val previewFiles = mutableMapOf<Int, File>()

        try {
            images.collect { item ->
                if (item.partial) {
                    val previewIndex = item.partialImageIndex ?: 0
                    deleteFileOnIo(previewFiles.remove(previewIndex))
                    val imageFile = saveImagePreview(item)
                    previewFiles[previewIndex] = imageFile
                    _currentGeneratedImages.value = finalImages + previewFiles
                        .toSortedMap()
                        .values
                        .map { preview ->
                            GeneratedImage(
                                id = 0,
                                prompt = prompt,
                                filePath = preview.absolutePath,
                                timestamp = System.currentTimeMillis(),
                                model = modelName,
                                provider = providerName,
                                seed = requestedSeed,
                            )
                        }
                } else {
                    onFinalImage()
                    val previewIndex = item.partialImageIndex
                    if (previewIndex != null) {
                        deleteFileOnIo(previewFiles.remove(previewIndex))
                    } else {
                        previewFiles.values.forEach { deleteFileOnIo(it) }
                        previewFiles.clear()
                    }
                    val savedImage = saveImageToStorage(
                        item = item,
                        prompt = prompt,
                        modelName = modelName,
                        providerName = providerName,
                        requestedSeed = requestedSeed,
                        type = type,
                        sourcePaths = sourcePaths,
                    )
                    finalImages.add(savedImage)
                    _currentGeneratedImages.value = finalImages + previewFiles
                        .toSortedMap()
                        .values
                        .map { preview ->
                            GeneratedImage(
                                id = 0,
                                prompt = prompt,
                                filePath = preview.absolutePath,
                                timestamp = System.currentTimeMillis(),
                                model = modelName,
                                provider = providerName,
                                seed = requestedSeed,
                            )
                        }
                }
            }
        } finally {
            withContext(NonCancellable) {
                previewFiles.values.forEach { deleteFileOnIo(it) }
            }
            if (previewFiles.isNotEmpty()) {
                _currentGeneratedImages.value = finalImages.toList()
            }
        }
    }

    private suspend fun saveImagePreview(item: ImageGenerationItem): File {
        var createdFile: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val imageFile = File(
                    getApplication<Application>().appTempFolder,
                    FileUtils.buildUuidFileName(displayName = null, mimeType = item.mimeType),
                )
                materializeImage(item, imageFile).also { createdFile = it }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                createdFile?.delete()
            }
            throw e
        }
    }

    private suspend fun saveImageToStorage(
        item: ImageGenerationItem,
        prompt: String,
        modelName: String,
        providerName: String,
        requestedSeed: Long?,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ): GeneratedImage = withContext(Dispatchers.IO) {
        val imagesDir = filesManager.getImagesDir()
        val timestamp = System.currentTimeMillis()
        val filename = FileUtils.buildUuidFileName(displayName = null, mimeType = item.mimeType)
        val imageFile = File(imagesDir, filename)
        val createdFile = materializeImage(item, imageFile)
        val metadata = readStoredImageMetadata(createdFile, item.mimeType)

        // Save to database with relative path
        val relativePath = "images/${imageFile.name}"
        val entity = GenMediaEntity(
            path = relativePath,
            modelId = modelName,
            prompt = prompt,
            createAt = timestamp,
            type = type,
            sourcePaths = sourcePaths,
            providerName = providerName,
            width = metadata.width,
            height = metadata.height,
            format = metadata.format,
            seed = item.seed ?: requestedSeed,
        )
        try {
            val insertedId = genMediaRepository.insertMedia(entity)
            GeneratedImage(
                id = Math.toIntExact(insertedId),
                prompt = prompt,
                filePath = createdFile.absolutePath,
                timestamp = timestamp,
                model = modelName,
                provider = providerName,
                width = metadata.width,
                height = metadata.height,
                format = metadata.format,
                seed = item.seed ?: requestedSeed,
                fileSizeBytes = createdFile.length().coerceAtLeast(0L),
                type = type,
            )
        } catch (e: Throwable) {
            createdFile.delete()
            throw e
        }
    }

    private suspend fun materializeImage(item: ImageGenerationItem, destination: File): File {
        destination.parentFile?.mkdirs()
        val stagingFile = File(destination.parentFile, ".${destination.name}.part")
        val temporaryFile = item.temporaryFilePath?.let(::File)
        var finalized = false
        stagingFile.delete()
        try {
            if (temporaryFile != null) {
                require(temporaryFile.isFile) { "Generated image temporary file is missing" }
                temporaryFile.inputStream().use { input ->
                    stagingFile.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } else {
                require(item.data.isNotBlank()) { "Generated image data is empty" }
                currentCoroutineContext().ensureActive()
                filesManager.createImageFileFromBase64(item.data, stagingFile.absolutePath)
                currentCoroutineContext().ensureActive()
            }
            require(stagingFile.length() > 0L) { "Generated image is empty" }
            check(stagingFile.renameTo(destination)) { "Failed to finalize generated image" }
            finalized = true
            currentCoroutineContext().ensureActive()
            return destination
        } catch (e: Throwable) {
            if (finalized) destination.delete()
            throw e
        } finally {
            stagingFile.delete()
            temporaryFile?.delete()
        }
    }

    private fun readStoredImageMetadata(file: File, fallbackMimeType: String): StoredImageMetadata {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }
        val detectedFormat = (options.outMimeType ?: fallbackMimeType)
            .substringBefore(';')
            .substringAfter('/')
            .lowercase()
            .let { if (it == "jpg") "jpeg" else it }
            .takeIf(String::isNotBlank)
        return StoredImageMetadata(
            width = options.outWidth.takeIf { it > 0 },
            height = options.outHeight.takeIf { it > 0 },
            format = detectedFormat,
        )
    }

    private fun List<CustomBody>.requestedSeed(): Long? = asReversed().firstNotNullOfOrNull { body ->
        if (body.key.substringAfterLast('.').equals("seed", ignoreCase = true)) {
            (body.value as? JsonPrimitive)?.longOrNull
        } else {
            body.value.findNestedSeed()
        }
    }

    private fun JsonElement.findNestedSeed(): Long? = when (this) {
        is JsonObject -> {
            entries.firstNotNullOfOrNull { (key, value) ->
                if (key.equals("seed", ignoreCase = true)) {
                    (value as? JsonPrimitive)?.longOrNull
                } else {
                    null
                }
            } ?: values.firstNotNullOfOrNull { it.findNestedSeed() }
        }
        is JsonArray -> firstNotNullOfOrNull { it.findNestedSeed() }
        else -> null
    }

    private suspend fun deleteFileOnIo(file: File?) = withContext(Dispatchers.IO) {
        file?.delete()
    }

    fun deleteImage(image: GeneratedImage) {
        deleteImages(listOf(image))
    }

    fun deleteImages(images: Collection<GeneratedImage>) {
        val persistedImages = images.filter { it.id > 0 }.distinctBy(GeneratedImage::id)
        if (persistedImages.isEmpty()) return
        viewModelScope.launch {
            _isGalleryMutating.value = true
            try {
                withContext(Dispatchers.IO) {
                    val files = persistedImages.map(::resolveGeneratedImageFile)
                    deleteFilesWithRollback(files) {
                        genMediaRepository.deleteMedia(persistedImages.map(GeneratedImage::id))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Image deletion failed (${e::class.simpleName})")
                _error.value = getApplication<Application>().getString(R.string.imggen_error_delete_failed)
            } finally {
                _isGalleryMutating.value = false
            }
        }
    }

    private fun resolveGeneratedImageFile(image: GeneratedImage): File {
        val imagesDirectory = filesManager.getImagesDir().canonicalFile
        val imageFile = File(image.filePath).canonicalFile
        check(imageFile.path.startsWith(imagesDirectory.path + File.separator)) {
            "Stored image path is outside the gallery directory"
        }
        return imageFile
    }

    private fun deleteReferenceFiles(paths: List<String>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                paths.forEach { path ->
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
        }
    }

    private fun userFacingError(error: Throwable): String {
        val context = getApplication<Application>()
        return when (error) {
            is ProviderRequestException -> error.statusCode?.let { statusCode ->
                context.getString(R.string.imggen_error_provider_http, statusCode)
            } ?: context.getString(R.string.imggen_error_provider)
            is IllegalArgumentException, is IllegalStateException ->
                context.getString(R.string.imggen_error_invalid_configuration)
            else -> context.getString(R.string.imggen_error_generic)
        }
    }

    private fun logImageError(titleRes: Int, summary: String, error: Throwable) {
        Logging.logError(
            name = getApplication<Application>().getString(titleRes),
            summary = summary,
            details = error.toDiagnosticMessage(),
            tag = TAG,
        )
    }

    companion object {
        private const val TAG = "ImgGenVM"
        private const val MAX_REFERENCE_IMAGES = 16
        private const val GALLERY_SEARCH_DEBOUNCE_MILLIS = 250L
        private const val LOCAL_IMPORT_MODEL_ID = "local-import"
    }
}

private data class StoredImageMetadata(
    val width: Int?,
    val height: Int?,
    val format: String?,
)

private data class StagedFileDeletion(
    val original: File,
    val tombstone: File,
)

internal suspend fun deleteFilesWithRollback(
    files: List<File>,
    deleteRecords: suspend () -> Unit,
) {
    val stagedFiles = mutableListOf<StagedFileDeletion>()
    var recordsDeleted = false
    try {
        files.distinctBy { it.absolutePath }.forEachIndexed { index, file ->
            if (!file.exists()) return@forEachIndexed
            val tombstone = File(
                file.parentFile,
                ".${file.name}.${System.nanoTime()}.$index.deleting",
            )
            check(file.renameTo(tombstone)) { "Unable to prepare image for deletion" }
            stagedFiles += StagedFileDeletion(file, tombstone)
        }

        deleteRecords()
        recordsDeleted = true

        stagedFiles.forEach { staged ->
            check(staged.tombstone.delete() || !staged.tombstone.exists()) {
                "Unable to permanently delete image"
            }
        }
    } catch (error: Throwable) {
        if (!recordsDeleted) {
            stagedFiles.asReversed().forEach { staged ->
                if (staged.tombstone.exists() && !staged.tombstone.renameTo(staged.original)) {
                    error.addSuppressed(IllegalStateException("Unable to restore image after deletion failure"))
                }
            }
        }
        throw error
    }
}
