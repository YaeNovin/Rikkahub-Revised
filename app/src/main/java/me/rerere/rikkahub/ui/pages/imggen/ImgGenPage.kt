package me.rerere.rikkahub.ui.pages.imggen

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog as AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.GeminiImageGenerationOptions
import me.rerere.ai.provider.GeminiSafetySettings
import me.rerere.ai.provider.GeminiSafetyThreshold
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderRequestChannel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.google.requestChannel
import me.rerere.ai.ui.ImageGenSize
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Colors
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.FolderRemove
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.MoveTo
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.formatUserFacingError
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

@Composable
fun ImageGenPage(
    modifier: Modifier = Modifier,
    vm: ImgGenVM = koinViewModel()
) {
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    var showCancelDialog by remember { mutableStateOf(false) }
    LaunchedEffect(error) {
        error?.let { errorMessage ->
            toaster.show(message = errorMessage, type = ToastType.Error)
            vm.clearError()
        }
    }
    BackHandler(isGenerating) {
        showCancelDialog = true
    }
    if (showCancelDialog) {
        CancelDialog(
            onDismiss = { showCancelDialog = false },
            onConfirm = {
                showCancelDialog = false
                vm.cancelGeneration()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.imggen_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(onClick = vm::startNewSession) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.imggen_page_new_session)
                        )
                    }
                }
            )
        },
        bottomBar = {
            BottomBar(pagerState, scope)
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) { page ->
            when (page) {
                0 -> ImageGenScreen(vm = vm)
                1 -> ImageGalleryScreen(vm = vm)
            }
        }
    }
}

@Composable
private fun CancelDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.imggen_page_cancel_generation_title)) },
        text = { Text(stringResource(R.string.imggen_page_cancel_generation_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.imggen_page_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.imggen_page_cancel))
            }
        }
    )
}

@Composable
private fun BottomBar(
    pagerState: PagerState,
    scope: CoroutineScope
) {
    NavigationBar {
        NavigationBarItem(
            selected = 0 == pagerState.currentPage,
            label = {
                Text(stringResource(R.string.imggen_page_title))
            },
            icon = {
                Icon(HugeIcons.Colors, null)
            },
            onClick = {
                scope.launch {
                    pagerState.animateScrollToPage(0)
                }
            }
        )

        NavigationBarItem(
            selected = 1 == pagerState.currentPage,
            label = {
                Text(stringResource(R.string.imggen_page_gallery))
            },
            icon = {
                Icon(HugeIcons.Image03, null)
            },
            onClick = {
                scope.launch {
                    pagerState.animateScrollToPage(1)
                }
            }
        )
    }
}

@Composable
private fun ImageGenScreen(
    vm: ImgGenVM,
) {
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val numberOfImages by vm.numberOfImages.collectAsStateWithLifecycle()
    val size by vm.size.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val outputFormat by vm.outputFormat.collectAsStateWithLifecycle()
    val background by vm.background.collectAsStateWithLifecycle()
    val outputCompression by vm.outputCompression.collectAsStateWithLifecycle()
    val resolution by vm.resolution.collectAsStateWithLifecycle()
    val thinkingLevel by vm.thinkingLevel.collectAsStateWithLifecycle()
    val geminiImageOptions by vm.geminiImageOptions.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val currentGeneratedImages by vm.currentGeneratedImages.collectAsStateWithLifecycle()
    val referenceImages by vm.referenceImages.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val selectedModel = settings.findModelById(settings.imageGenerationModelId)
    val selectedProvider = selectedModel?.findProvider(settings.providers)
    val selectedConstraints = if (selectedModel != null && selectedProvider != null) {
        vm.providerManager.imageGenerationConstraints(selectedProvider, selectedModel)
    } else {
        null
    }
    val geminiProvider = selectedProvider as? ProviderSetting.Google
    val isGeminiImageModel = geminiProvider != null &&
        selectedConstraints?.sizeRequestField == "aspect_ratio" &&
        selectedModel?.modelId?.substringAfterLast('/')?.startsWith("gemini-", ignoreCase = true) == true
    val geminiModelId = selectedModel?.modelId?.takeIf { isGeminiImageModel }
    val geminiRequestChannel = geminiProvider?.requestChannel()?.takeIf { isGeminiImageModel }
    val maxOutputImages = selectedConstraints?.maxOutputImages ?: 1
    val maxReferenceImages = selectedConstraints?.takeIf { it.supportsEdit }?.maxReferenceImages ?: 0
    val supportedSizes = selectedConstraints?.supportedSizes
    val supportsSize = selectedConstraints?.supportsSize ?: true
    var showSettingsSheet by remember { mutableStateOf(false) }
    var settingsPresentationId by remember { mutableIntStateOf(0) }

    LaunchedEffect(
        selectedModel?.id,
        selectedProvider?.id,
        maxOutputImages,
        maxReferenceImages,
        supportsSize,
        supportedSizes,
        outputFormat,
    ) {
        if (numberOfImages > maxOutputImages) vm.updateNumberOfImages(maxOutputImages)
        vm.limitReferenceImages(maxReferenceImages)
        if (!supportsSize) {
            vm.updateSize(ImageGenSize.AUTO.value)
        } else if (selectedConstraints?.supportsCustomSize != true && supportedSizes?.let { size !in it } == true) {
            vm.updateSize(supportedSizes.firstOrNull() ?: ImageGenSize.AUTO.value)
        }
        if (quality !in selectedConstraints?.supportedQualityValues.orEmpty()) vm.updateQuality(null)
        if (outputFormat !in selectedConstraints?.supportedOutputFormats.orEmpty()) vm.updateOutputFormat(null)
        if (background !in selectedConstraints?.supportedBackgroundValues.orEmpty()) vm.updateBackground(null)
        if (background == "transparent" && outputFormat !in setOf(null, "png", "webp")) vm.updateBackground(null)
        if (resolution !in selectedConstraints?.supportedResolutionValues.orEmpty()) vm.updateResolution(null)
        if (thinkingLevel !in selectedConstraints?.supportedThinkingValues.orEmpty()) {
            vm.updateThinkingLevel(null)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                currentGeneratedImages.chunked(2).forEach { rowImages ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowImages.forEach { image ->
                            key(image.filePath) {
                                var showPreview by remember { mutableStateOf(false) }
                                AsyncImage(
                                    model = File(image.filePath),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showPreview = true },
                                    contentScale = ContentScale.Crop,
                                )

                                if (showPreview) {
                                    ImagePreviewDialog(
                                        images = listOf(image.filePath),
                                        onDismissRequest = { showPreview = false },
                                    )
                                }
                            }
                        }
                        if (rowImages.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            if (isGenerating) {
                ContainedLoadingIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        InputBar(
            prompt = prompt,
            vm = vm,
            isGenerating = isGenerating,
            referenceImages = referenceImages,
            settings = settings,
            onShowSettings = {
                settingsPresentationId++
                showSettingsSheet = true
            },
            modifier = Modifier
        )
    }

    if (showSettingsSheet) {
        key(settingsPresentationId) {
            val settingsSheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            )
            SettingsBottomSheet(
                vm = vm,
                numberOfImages = numberOfImages,
                size = size,
                maxOutputImages = maxOutputImages,
                supportsSize = supportsSize,
                supportedSizes = supportedSizes,
                supportsCustomSize = selectedConstraints?.supportsCustomSize ?: true,
                groupSizesByAspectRatio = selectedConstraints?.groupSizesByAspectRatio == true,
                customSizeMultiple = selectedConstraints?.customSizeMultiple,
                customSizeMaxDimension = selectedConstraints?.customSizeMaxDimension,
                customSizeMinPixels = selectedConstraints?.customSizeMinPixels,
                customSizeMaxPixels = selectedConstraints?.customSizeMaxPixels,
                customSizeMaxAspectRatio = selectedConstraints?.customSizeMaxAspectRatio,
                sizeRequestField = selectedConstraints?.sizeRequestField ?: "size",
                quality = quality,
                outputFormat = outputFormat,
                background = background,
                outputCompression = outputCompression,
                resolution = resolution,
                thinkingLevel = thinkingLevel,
                geminiImageOptions = geminiImageOptions,
                supportedQualityValues = selectedConstraints?.supportedQualityValues.orEmpty(),
                supportedOutputFormats = selectedConstraints?.supportedOutputFormats.orEmpty(),
                supportedBackgroundValues = selectedConstraints?.supportedBackgroundValues.orEmpty(),
                supportsOutputCompression = selectedConstraints?.supportsOutputCompression == true,
                supportedResolutionValues = selectedConstraints?.supportedResolutionValues.orEmpty(),
                supportedThinkingValues = selectedConstraints?.supportedThinkingValues.orEmpty(),
                supportsGeminiTextResponse = selectedConstraints?.supportsTextResponse == true,
                supportsGeminiSafetySettings = selectedConstraints?.supportsSafetySettings == true,
                supportsGeminiWebSearch = selectedConstraints?.supportsWebSearchGrounding == true,
                supportsGeminiImageSearch = selectedConstraints?.supportsImageSearchGrounding == true,
                geminiModelId = geminiModelId,
                geminiRequestChannel = geminiRequestChannel,
                referenceImageCount = referenceImages.size,
                sheetState = settingsSheetState,
                onDismiss = { showSettingsSheet = false }
            )
        }
    }
}

@Composable
private fun InputBar(
    prompt: String,
    vm: ImgGenVM,
    isGenerating: Boolean,
    referenceImages: List<String>,
    settings: Settings,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedModel = settings.findModelById(settings.imageGenerationModelId)
    val selectedProvider = selectedModel?.findProvider(settings.providers)
    val selectedConstraints = if (selectedModel != null && selectedProvider != null) {
        vm.providerManager.imageGenerationConstraints(selectedProvider, selectedModel)
    } else {
        null
    }
    val editing = referenceImages.isNotEmpty()
    val selectableProviders = remember(settings.providers, editing) {
        settings.providers.mapNotNull { provider ->
            val supportedModels = provider.models.mapNotNull { model ->
                val effectiveProvider = model.findProvider(settings.providers) ?: return@mapNotNull null
                val constraints = vm.providerManager.imageGenerationConstraints(effectiveProvider, model)
                val supported = if (editing) constraints.supportsEdit else constraints.supportsGeneration
                model.copy(type = ModelType.IMAGE).takeIf { supported }
            }
            provider.copyProvider(models = supportedModels).takeIf { supportedModels.isNotEmpty() }
        }
    }
    val maxReferenceImages = selectedConstraints?.maxReferenceImages ?: 0
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            val remainingSlots = (maxReferenceImages - referenceImages.size).coerceAtLeast(0)
            val acceptedUris = selectedUris.take(remainingSlots)
            if (acceptedUris.isNotEmpty()) {
                scope.launch {
                    val paths = acceptedUris.mapNotNull { uri ->
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val bitmap = ImageUtils.loadOptimizedBitmap(
                                    context = context,
                                    uri = uri,
                                    maxSize = 2048,
                                    preferredConfig = android.graphics.Bitmap.Config.ARGB_8888,
                                )
                                    ?: error("Failed to decode image")
                                val file = File(context.appTempFolder, "imggen_ref_${Uuid.random()}.png")
                                val stagingFile = File(file.parentFile, ".${file.name}.part")
                                try {
                                    stagingFile.outputStream().use { output ->
                                        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                                    }
                                    check(stagingFile.renameTo(file))
                                    file.absolutePath
                                } finally {
                                    bitmap.recycle()
                                    stagingFile.delete()
                                }
                            }.getOrNull()
                        }
                    }
                    vm.addReferenceImages(paths)
                }
            }
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (referenceImages.isNotEmpty()) {
            ReferenceImagesRow(
                images = referenceImages,
                onRemove = vm::removeReferenceImage,
                enabled = !isGenerating,
            )
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = vm::updatePrompt,
            placeholder = { Text(stringResource(R.string.imggen_page_prompt_placeholder)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp),
            minLines = 1,
            maxLines = 5,
            shape = MaterialTheme.shapes.large,
            textStyle = MaterialTheme.typography.bodySmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelSelector(
                modelId = settings.imageGenerationModelId,
                providers = selectableProviders,
                type = ModelType.IMAGE,
                onlyIcon = true,
                onSelect = { model ->
                    scope.launch {
                        vm.settingsStore.update { oldSettings ->
                            oldSettings.copy(imageGenerationModelId = model.id)
                        }
                    }
                }
            )

            IconButton(
                onClick = onShowSettings
            ) {
                Icon(HugeIcons.Tools, null)
            }

            IconButton(
                onClick = { imagePickerLauncher.launch("image/*") },
                enabled = !isGenerating && selectedConstraints?.supportsEdit == true &&
                    referenceImages.size < maxReferenceImages,
            ) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = stringResource(R.string.imggen_page_add_reference_image)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            val canSend = prompt.isNotBlank() && selectedProvider != null && (
                if (editing) selectedConstraints?.supportsEdit == true
                else selectedConstraints?.supportsGeneration == true
            )
            Surface(
                onClick = {
                    if (!isGenerating) {
                        if (referenceImages.isEmpty()) {
                            vm.generateImage()
                        } else {
                            vm.editImage()
                        }
                    } else {
                        vm.cancelGeneration()
                    }
                },
                enabled = isGenerating || canSend,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = when {
                    isGenerating -> MaterialTheme.colorScheme.errorContainer
                    !canSend -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> MaterialTheme.colorScheme.primary
                },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isGenerating) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
                        contentDescription = stringResource(R.string.imggen_page_generate_image),
                        tint = when {
                            isGenerating -> MaterialTheme.colorScheme.onErrorContainer
                            !canSend -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else -> MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReferenceImagesRow(
    images: List<String>,
    onRemove: (String) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEach { image ->
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box {
                    AsyncImage(
                        model = File(image),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    Surface(
                        onClick = { onRemove(image) },
                        enabled = enabled,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(20.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.imggen_page_remove_reference_image),
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGalleryScreen(
    vm: ImgGenVM,
) {
    val generatedImages = vm.generatedImages.collectAsLazyPagingItems()
    val galleryQuery by vm.galleryQuery.collectAsStateWithLifecycle()
    val folders by vm.galleryFolders.collectAsStateWithLifecycle()
    val selectedFolderId by vm.selectedGalleryFolderId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val pullToRefreshState = rememberPullToRefreshState()
    val isGalleryMutating by vm.isGalleryMutating.collectAsStateWithLifecycle()
    val unknownValue = stringResource(R.string.imggen_page_metadata_unknown)
    var previewImage by remember { mutableStateOf<GeneratedImage?>(null) }
    var detailsImage by remember { mutableStateOf<GeneratedImage?>(null) }
    var imagesPendingMove by remember { mutableStateOf<List<GeneratedImage>?>(null) }
    var imagesPendingDeletion by remember { mutableStateOf<List<GeneratedImage>?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedImages by remember { mutableStateOf<Map<Int, GeneratedImage>>(emptyMap()) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderPendingDeletion by remember { mutableStateOf<String?>(null) }
    var folderPendingDissolution by remember { mutableStateOf<String?>(null) }

    val galleryImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { selectedUris ->
        if (selectedUris.isNotEmpty()) {
            vm.importGalleryImages(selectedUris, selectedFolderId)
        }
    }

    fun clearSelection() {
        selectedImages = emptyMap()
        selectionMode = false
    }

    fun toggleSelection(image: GeneratedImage) {
        selectionMode = true
        selectedImages = if (image.id in selectedImages) {
            selectedImages - image.id
        } else {
            selectedImages + (image.id to image)
        }
    }

    BackHandler(selectionMode) { clearSelection() }
    LaunchedEffect(galleryQuery, selectedFolderId) {
        clearSelection()
    }

    fun saveImage(image: GeneratedImage) {
        scope.launch {
            try {
                filesManager.saveMessageImage(context, "file://${image.filePath}")
                toaster.show(
                    message = context.getString(R.string.imggen_page_image_saved_success),
                    type = ToastType.Success,
                )
            } catch (e: Exception) {
                toaster.show(
                    message = context.formatUserFacingError(e),
                    type = ToastType.Error,
                )
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selectionMode) {
                    stringResource(R.string.imggen_page_gallery_selected_count, selectedImages.size)
                } else {
                    stringResource(R.string.imggen_page_gallery_manage)
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (isGalleryMutating) {
                CircularWavyProgressIndicator(modifier = Modifier.size(28.dp))
            } else if (selectionMode) {
                if (selectedImages.isNotEmpty()) {
                    Tooltip(tooltip = { Text(stringResource(R.string.imggen_page_gallery_move_selected)) }) {
                        IconButton(onClick = { imagesPendingMove = selectedImages.values.toList() }) {
                            Icon(
                                HugeIcons.MoveTo,
                                contentDescription = stringResource(R.string.imggen_page_gallery_move_selected),
                            )
                        }
                    }
                    Tooltip(tooltip = { Text(stringResource(R.string.imggen_page_gallery_delete_selected)) }) {
                        IconButton(onClick = { imagesPendingDeletion = selectedImages.values.toList() }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.imggen_page_gallery_delete_selected),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Tooltip(tooltip = { Text(stringResource(R.string.imggen_page_gallery_cancel_selection)) }) {
                    IconButton(onClick = ::clearSelection) {
                        Icon(
                            HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.imggen_page_gallery_cancel_selection),
                        )
                    }
                }
            } else {
                Tooltip(tooltip = { Text(stringResource(R.string.imggen_page_gallery_add_images)) }) {
                    IconButton(onClick = { galleryImportLauncher.launch("image/*") }) {
                        Icon(
                            HugeIcons.Add01,
                            contentDescription = stringResource(R.string.imggen_page_gallery_add_images),
                        )
                    }
                }
                Tooltip(tooltip = { Text(stringResource(R.string.imggen_page_gallery_select_images)) }) {
                    IconButton(onClick = { selectionMode = true }) {
                        Icon(
                            HugeIcons.CheckmarkCircle02,
                            contentDescription = stringResource(R.string.imggen_page_gallery_select_images),
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = galleryQuery,
            onValueChange = vm::updateGalleryQuery,
            placeholder = { Text(stringResource(R.string.imggen_page_gallery_search_hint)) },
            leadingIcon = {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                )
            },
            trailingIcon = if (galleryQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { vm.updateGalleryQuery("") }) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.imggen_page_gallery_search_clear),
                        )
                    }
                }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = selectedFolderId == null,
                onClick = { vm.selectGalleryFolder(null) },
                leadingIcon = {
                    Icon(HugeIcons.Image03, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                label = { Text(stringResource(R.string.imggen_page_gallery_all)) },
            )
            folders.forEach { folder ->
                FilterChip(
                    selected = selectedFolderId == folder.id,
                    onClick = { vm.selectGalleryFolder(folder.id) },
                    leadingIcon = {
                        Icon(HugeIcons.Folder01, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    label = {
                        Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
            IconButton(onClick = { showCreateFolderDialog = true }) {
                Icon(
                    imageVector = HugeIcons.FolderAdd,
                    contentDescription = stringResource(R.string.imggen_page_gallery_new_folder),
                )
            }
            selectedFolderId?.let { folderId ->
                Tooltip(
                    tooltip = { Text(stringResource(R.string.imggen_page_gallery_delete_folder)) },
                ) {
                    IconButton(onClick = { folderPendingDeletion = folderId }) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.imggen_page_gallery_delete_folder),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Tooltip(
                    tooltip = { Text(stringResource(R.string.imggen_page_gallery_dissolve_folder)) },
                ) {
                    IconButton(onClick = { folderPendingDissolution = folderId }) {
                        Icon(
                            imageVector = HugeIcons.FolderRemove,
                            contentDescription = stringResource(R.string.imggen_page_gallery_dissolve_folder),
                        )
                    }
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { generatedImages.refresh() },
            state = pullToRefreshState,
            modifier = Modifier.weight(1f),
        ) {
            if (generatedImages.itemCount == 0) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = HugeIcons.Image03,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(
                                if (galleryQuery.isBlank()) {
                                    R.string.imggen_page_no_generated_images
                                } else {
                                    R.string.imggen_page_gallery_no_results
                                }
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        count = generatedImages.itemCount,
                        key = generatedImages.itemKey { it.id },
                        contentType = generatedImages.itemContentType { "GeneratedImage" },
                    ) { index ->
                        val image = generatedImages[index]
                        image?.let {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .combinedClickable(
                                        onClick = {
                                            if (selectionMode) toggleSelection(it) else previewImage = it
                                        },
                                        onLongClick = { toggleSelection(it) },
                                    ),
                                shape = RoundedCornerShape(4.dp),
                                border = if (it.id in selectedImages) {
                                    BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    null
                                },
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = File(it.filePath),
                                        contentDescription = it.prompt,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                    if (selectionMode) {
                                        Surface(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(6.dp),
                                            shape = CircleShape,
                                            color = if (it.id in selectedImages) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHighest
                                            },
                                            contentColor = if (it.id in selectedImages) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        ) {
                                            Icon(
                                                HugeIcons.CheckmarkCircle02,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .padding(4.dp)
                                                    .size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    previewImage?.let { image ->
        ImagePreviewDialog(
            images = listOf(image.filePath),
            onDismissRequest = { previewImage = null },
            topEndAction = {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    tonalElevation = 3.dp,
                ) {
                    IconButton(onClick = { detailsImage = image }) {
                        Icon(
                            imageVector = HugeIcons.InformationCircle,
                            contentDescription = stringResource(R.string.imggen_page_image_details),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
        )
    }

    detailsImage?.let { image ->
        val folderName = folders.firstOrNull { it.id == image.folderId }?.name
            ?: stringResource(R.string.imggen_page_gallery_unfiled)
        GalleryDetailsDialog(
            image = image,
            modelName = if (image.type == GenMediaEntity.TYPE_IMAGE_IMPORT) {
                stringResource(R.string.imggen_page_gallery_local_import)
            } else {
                image.model
            },
            folderName = folderName,
            unknownValue = unknownValue,
            fileSize = Formatter.formatShortFileSize(context, image.fileSizeBytes),
            onDismiss = { detailsImage = null },
            onCopyPrompt = {
                clipboardManager.setText(AnnotatedString(image.prompt))
                toaster.show(
                    message = context.getString(R.string.imggen_page_prompt_copied),
                    type = ToastType.Success,
                )
            },
            onSave = { saveImage(image) },
            onMove = {
                detailsImage = null
                imagesPendingMove = listOf(image)
            },
            onDelete = {
                detailsImage = null
                previewImage = null
                vm.deleteImage(image)
            },
        )
    }

    imagesPendingMove?.let { images ->
        val currentFolderIds = images.map(GeneratedImage::folderId).distinct()
        MoveImageToFolderDialog(
            currentFolderId = currentFolderIds.singleOrNull(),
            highlightCurrentFolder = currentFolderIds.size == 1,
            folders = folders.map { it.id to it.name },
            onDismiss = { imagesPendingMove = null },
            onMove = { folderId ->
                vm.moveImagesToFolder(images, folderId)
                imagesPendingMove = null
                previewImage = null
                clearSelection()
            },
        )
    }

    imagesPendingDeletion?.let { images ->
        AlertDialog(
            onDismissRequest = { imagesPendingDeletion = null },
            title = { Text(stringResource(R.string.imggen_page_gallery_delete_selected)) },
            text = {
                Text(stringResource(R.string.imggen_page_gallery_delete_selected_message, images.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteImages(images)
                        imagesPendingDeletion = null
                        previewImage = null
                        clearSelection()
                    },
                ) {
                    Text(stringResource(R.string.imggen_page_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { imagesPendingDeletion = null }) {
                    Text(stringResource(R.string.imggen_page_cancel))
                }
            },
        )
    }

    if (showCreateFolderDialog) {
        CreateGalleryFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onCreate = { name ->
                vm.createGalleryFolder(name)
                showCreateFolderDialog = false
            },
        )
    }

    folderPendingDeletion?.let { folderId ->
        AlertDialog(
            onDismissRequest = { folderPendingDeletion = null },
            title = { Text(stringResource(R.string.imggen_page_gallery_delete_folder)) },
            text = { Text(stringResource(R.string.imggen_page_gallery_delete_folder_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteGalleryFolderWithContents(folderId)
                        folderPendingDeletion = null
                    },
                ) {
                    Text(stringResource(R.string.imggen_page_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { folderPendingDeletion = null }) {
                    Text(stringResource(R.string.imggen_page_cancel))
                }
            },
        )
    }

    folderPendingDissolution?.let { folderId ->
        AlertDialog(
            onDismissRequest = { folderPendingDissolution = null },
            title = { Text(stringResource(R.string.imggen_page_gallery_dissolve_folder)) },
            text = { Text(stringResource(R.string.imggen_page_gallery_dissolve_folder_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.dissolveGalleryFolder(folderId)
                        folderPendingDissolution = null
                    },
                ) {
                    Text(stringResource(R.string.imggen_page_gallery_dissolve))
                }
            },
            dismissButton = {
                TextButton(onClick = { folderPendingDissolution = null }) {
                    Text(stringResource(R.string.imggen_page_cancel))
                }
            },
        )
    }
}

@Composable
private fun GalleryDetailsDialog(
    image: GeneratedImage,
    modelName: String,
    folderName: String,
    unknownValue: String,
    fileSize: String,
    onDismiss: () -> Unit,
    onCopyPrompt: () -> Unit,
    onSave: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val dimensions = if (image.width != null && image.height != null) {
        "${image.width}x${image.height}"
    } else {
        unknownValue
    }
    val aspectRatio = imageAspectRatio(image.width, image.height) ?: unknownValue

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(HugeIcons.InformationCircle, contentDescription = null) },
        title = { Text(stringResource(R.string.imggen_page_image_details)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GalleryMetadataText(stringResource(R.string.imggen_page_metadata_model), modelName)
                GalleryMetadataText(
                    stringResource(R.string.imggen_page_metadata_provider),
                    image.provider ?: unknownValue,
                )
                GalleryMetadataText(stringResource(R.string.imggen_page_metadata_aspect_ratio), aspectRatio)
                GalleryMetadataText(stringResource(R.string.imggen_page_metadata_size), dimensions)
                GalleryMetadataText(stringResource(R.string.imggen_page_metadata_file_size), fileSize)
                GalleryMetadataText(
                    stringResource(R.string.imggen_page_metadata_format),
                    image.format?.uppercase() ?: unknownValue,
                )
                GalleryMetadataText(
                    stringResource(R.string.imggen_page_metadata_seed),
                    image.seed?.toString() ?: unknownValue,
                )
                GalleryMetadataText(stringResource(R.string.imggen_page_metadata_folder), folderName)
                GalleryMetadataText(
                    stringResource(R.string.imggen_page_metadata_generated_at),
                    Instant.ofEpochMilli(image.timestamp).toLocalDateTime(),
                )
                Text(
                    text = image.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onCopyPrompt) {
                        Icon(HugeIcons.Copy01, stringResource(R.string.imggen_page_copy_prompt))
                    }
                    IconButton(onClick = onSave) {
                        Icon(HugeIcons.FloppyDisk, stringResource(R.string.imggen_page_save))
                    }
                    IconButton(onClick = onMove) {
                        Icon(HugeIcons.MoveTo, stringResource(R.string.imggen_page_gallery_move_to_folder))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            HugeIcons.Delete01,
                            stringResource(R.string.imggen_page_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.imggen_page_close))
            }
        },
    )
}

@Composable
private fun MoveImageToFolderDialog(
    currentFolderId: String?,
    highlightCurrentFolder: Boolean = true,
    folders: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.imggen_page_gallery_move_to_folder)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GalleryFolderChoice(
                    name = stringResource(R.string.imggen_page_gallery_unfiled),
                    selected = highlightCurrentFolder && currentFolderId == null,
                    onClick = { onMove(null) },
                )
                folders.forEach { (id, name) ->
                    GalleryFolderChoice(
                        name = name,
                        selected = highlightCurrentFolder && currentFolderId == id,
                        onClick = { onMove(id) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.imggen_page_cancel))
            }
        },
    )
}

@Composable
private fun GalleryFolderChoice(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(HugeIcons.Folder01, contentDescription = null)
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CreateGalleryFolderDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.imggen_page_gallery_new_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.imggen_page_gallery_folder_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.imggen_page_gallery_create_folder))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.imggen_page_cancel))
            }
        },
    )
}

@Composable
private fun GalleryMetadataText(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun imageAspectRatio(width: Int?, height: Int?): String? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val divisor = greatestCommonDivisor(width, height)
    return "${width / divisor}:${height / divisor}"
}

private tailrec fun greatestCommonDivisor(left: Int, right: Int): Int =
    if (right == 0) left else greatestCommonDivisor(right, left % right)

private val IMAGE_SIZE_PATTERN = Regex("(\\d+)x(\\d+)")

private data class ImageSizePresetGroup(
    val aspectRatio: String,
    val sizes: List<String>,
)

private fun groupImageSizesByAspectRatio(sizes: Set<String>): List<ImageSizePresetGroup> = sizes
    .asSequence()
    .filterNot { it == ImageGenSize.AUTO.value }
    .mapNotNull { value ->
        val match = IMAGE_SIZE_PATTERN.matchEntire(value) ?: return@mapNotNull null
        val width = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val height = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        val divisor = greatestCommonDivisor(width, height)
        val reducedRatio = "${width / divisor}:${height / divisor}"
        val displayRatio = when (reducedRatio) {
            "8:5" -> "16:10"
            "5:8" -> "10:16"
            "7:3" -> "21:9"
            "3:7" -> "9:21"
            else -> reducedRatio
        }
        displayRatio to value
    }
    .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    .map { (aspectRatio, values) -> ImageSizePresetGroup(aspectRatio, values) }

private fun isValidCustomImageSize(
    value: String,
    multiple: Int?,
    maxDimension: Int?,
    minPixels: Long?,
    maxPixels: Long?,
    maxAspectRatio: Int?,
): Boolean {
    val match = IMAGE_SIZE_PATTERN.matchEntire(value.trim().lowercase()) ?: return false
    val width = match.groupValues[1].toIntOrNull() ?: return false
    val height = match.groupValues[2].toIntOrNull() ?: return false
    if (width <= 0 || height <= 0) return false
    if (multiple != null && (width % multiple != 0 || height % multiple != 0)) return false
    if (maxDimension != null && (width > maxDimension || height > maxDimension)) return false
    val pixels = width.toLong() * height.toLong()
    if (minPixels != null && pixels < minPixels) return false
    if (maxPixels != null && pixels > maxPixels) return false
    if (maxAspectRatio != null) {
        val longEdge = maxOf(width, height).toLong()
        val shortEdge = minOf(width, height).toLong()
        if (longEdge > shortEdge * maxAspectRatio) return false
    }
    return true
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsBottomSheet(
    vm: ImgGenVM,
    numberOfImages: Int,
    size: String,
    maxOutputImages: Int,
    supportsSize: Boolean,
    supportedSizes: Set<String>?,
    supportsCustomSize: Boolean,
    groupSizesByAspectRatio: Boolean,
    customSizeMultiple: Int?,
    customSizeMaxDimension: Int?,
    customSizeMinPixels: Long?,
    customSizeMaxPixels: Long?,
    customSizeMaxAspectRatio: Int?,
    sizeRequestField: String,
    quality: String?,
    outputFormat: String?,
    background: String?,
    outputCompression: Int,
    resolution: String?,
    thinkingLevel: String?,
    geminiImageOptions: GeminiImageGenerationOptions,
    supportedQualityValues: Set<String>,
    supportedOutputFormats: Set<String>,
    supportedBackgroundValues: Set<String>,
    supportsOutputCompression: Boolean,
    supportedResolutionValues: Set<String>,
    supportedThinkingValues: Set<String>,
    supportsGeminiTextResponse: Boolean,
    supportsGeminiSafetySettings: Boolean,
    supportsGeminiWebSearch: Boolean,
    supportsGeminiImageSearch: Boolean,
    geminiModelId: String?,
    geminiRequestChannel: ProviderRequestChannel?,
    referenceImageCount: Int,
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    var showGeminiParameterSummary by remember { mutableStateOf(false) }
    var showGeminiSafetySettings by remember { mutableStateOf(false) }
    if (showGeminiParameterSummary && geminiModelId != null && geminiRequestChannel != null) {
        GeminiImageParameterDialog(
            modelId = geminiModelId,
            channel = geminiRequestChannel,
            aspectRatio = size.takeUnless { it == ImageGenSize.AUTO.value },
            resolution = resolution,
            thinkingLevel = thinkingLevel,
            geminiImageOptions = geminiImageOptions.copy(
                includeTextResponse = geminiImageOptions.includeTextResponse && supportsGeminiTextResponse,
                webSearchGrounding = geminiImageOptions.webSearchGrounding && supportsGeminiWebSearch,
                imageSearchGrounding = geminiImageOptions.imageSearchGrounding && supportsGeminiImageSearch,
            ),
            referenceImageCount = referenceImageCount,
            onDismiss = { showGeminiParameterSummary = false },
        )
    }
    if (showGeminiSafetySettings) {
        GeminiImageSafetyDialog(
            settings = geminiImageOptions.safetySettings,
            onSettingsChange = vm::updateGeminiSafetySettings,
            onDismiss = { showGeminiSafetySettings = false },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.imggen_page_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (geminiModelId != null && geminiRequestChannel != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showGeminiParameterSummary = true },
                ) {
                    FormItem(
                        modifier = Modifier.padding(12.dp),
                        label = { Text(stringResource(R.string.imggen_page_gemini_parameters_title)) },
                        description = {
                            Text(stringResource(R.string.imggen_page_gemini_parameters_desc))
                            Text(stringResource(R.string.imggen_page_gemini_parameters_experimental))
                        },
                        tail = {
                            Icon(
                                imageVector = HugeIcons.InformationCircle,
                                contentDescription = stringResource(
                                    R.string.imggen_page_gemini_parameters_details,
                                ),
                            )
                        },
                    ) {
                        Text(
                            text = geminiRequestChannel.displayName(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            FormItem(
                label = { Text(stringResource(R.string.imggen_page_generation_count)) },
                description = { Text(stringResource(R.string.imggen_page_generation_count_desc)) }
            ) {
                OutlinedNumberInput(
                    value = numberOfImages,
                    onValueChange = { vm.updateNumberOfImages(it.coerceAtMost(maxOutputImages)) },
                    modifier = Modifier.width(120.dp)
                )
            }

            if (supportsSize) {
                FormItem(
                    label = {
                        Text(
                            stringResource(
                                if (sizeRequestField == "aspect_ratio") {
                                    R.string.imggen_page_aspect_ratio
                                } else {
                                    R.string.imggen_page_image_size
                                }
                            )
                        )
                    },
                    description = if (geminiModelId != null) {
                        {
                            Text(stringResource(R.string.imggen_page_gemini_aspect_ratio_desc))
                        }
                    } else {
                        null
                    },
                ) {
                    val sizeOptions = supportedSizes ?: ImageGenSize.entries
                        .mapTo(linkedSetOf()) { it.value }
                    if (groupSizesByAspectRatio) {
                        val presetGroups = remember(sizeOptions) {
                            groupImageSizesByAspectRatio(sizeOptions)
                        }
                        val selectedSizeGroup = presetGroups.firstOrNull { size in it.sizes }
                        var selectedAspectRatio by remember(presetGroups) {
                            mutableStateOf(selectedSizeGroup?.aspectRatio ?: presetGroups.firstOrNull()?.aspectRatio)
                        }
                        LaunchedEffect(size, presetGroups) {
                            presetGroups.firstOrNull { size in it.sizes }?.let {
                                selectedAspectRatio = it.aspectRatio
                            }
                        }

                        Text(
                            text = stringResource(R.string.imggen_page_standard_aspect_ratio),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (ImageGenSize.AUTO.value in sizeOptions) {
                                FilterChip(
                                    selected = size == ImageGenSize.AUTO.value,
                                    onClick = { vm.updateSize(ImageGenSize.AUTO.value) },
                                    label = { Text(stringResource(R.string.imggen_page_model_default)) },
                                )
                            }
                            presetGroups.forEach { group ->
                                FilterChip(
                                    selected = size in group.sizes,
                                    onClick = {
                                        selectedAspectRatio = group.aspectRatio
                                        vm.updateSize(group.sizes.first())
                                    },
                                    label = { Text(group.aspectRatio) },
                                )
                            }
                        }

                        presetGroups.firstOrNull { it.aspectRatio == selectedAspectRatio }?.let { group ->
                            Text(
                                text = stringResource(R.string.imggen_page_standard_size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                group.sizes.forEach { sizeOption ->
                                    FilterChip(
                                        selected = size == sizeOption,
                                        onClick = { vm.updateSize(sizeOption) },
                                        label = { Text(sizeOption) },
                                    )
                                }
                            }
                        }
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            sizeOptions.forEach { sizeOption ->
                                FilterChip(
                                    selected = size == sizeOption,
                                    onClick = { vm.updateSize(sizeOption) },
                                    label = {
                                        Text(
                                            if (sizeOption == ImageGenSize.AUTO.value) {
                                                stringResource(R.string.imggen_page_model_default)
                                            } else {
                                                sizeOption
                                            }
                                        )
                                    },
                                )
                            }
                        }
                    }

                    if (supportsCustomSize) {
                        Spacer(modifier = Modifier.height(8.dp))
                        var customSizeText by remember {
                            mutableStateOf(size.takeUnless { it in sizeOptions }.orEmpty())
                        }
                        LaunchedEffect(size, sizeOptions) {
                            if (size in sizeOptions) customSizeText = ""
                        }
                        val hasCustomConstraints = customSizeMultiple != null ||
                            customSizeMaxDimension != null || customSizeMinPixels != null ||
                            customSizeMaxPixels != null || customSizeMaxAspectRatio != null
                        val customSizeValid = customSizeText.isBlank() || !hasCustomConstraints ||
                            isValidCustomImageSize(
                                value = customSizeText,
                                multiple = customSizeMultiple,
                                maxDimension = customSizeMaxDimension,
                                minPixels = customSizeMinPixels,
                                maxPixels = customSizeMaxPixels,
                                maxAspectRatio = customSizeMaxAspectRatio,
                            )
                        OutlinedTextField(
                            value = customSizeText,
                            onValueChange = { value ->
                                customSizeText = value
                                vm.updateSize(value.trim())
                            },
                            label = { Text(stringResource(R.string.imggen_page_custom_size)) },
                            placeholder = { Text(stringResource(R.string.imggen_page_custom_size_example)) },
                            supportingText = if (groupSizesByAspectRatio) {
                                {
                                    Text(
                                        stringResource(
                                            if (customSizeValid) {
                                                R.string.imggen_page_openai_size_requirements
                                            } else {
                                                R.string.imggen_page_custom_size_invalid
                                            }
                                        )
                                    )
                                }
                            } else {
                                null
                            },
                            isError = !customSizeValid,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (supportedQualityValues.isNotEmpty()) {
                ImageOptionChips(
                    title = stringResource(R.string.imggen_page_quality),
                    value = quality,
                    options = supportedQualityValues,
                    onValueChange = vm::updateQuality,
                )
            }

            if (supportedOutputFormats.isNotEmpty()) {
                ImageOptionChips(
                    title = stringResource(R.string.imggen_page_output_format),
                    value = outputFormat,
                    options = supportedOutputFormats,
                    onValueChange = vm::updateOutputFormat,
                )
            }

            if (supportedBackgroundValues.isNotEmpty()) {
                ImageOptionChips(
                    title = stringResource(R.string.imggen_page_background),
                    value = background,
                    options = supportedBackgroundValues,
                    onValueChange = vm::updateBackground,
                )
            }

            if (supportsOutputCompression && outputFormat in setOf("jpeg", "webp")) {
                FormItem(
                    label = { Text(stringResource(R.string.imggen_page_output_compression)) },
                    description = { Text(stringResource(R.string.imggen_page_output_compression_desc)) },
                ) {
                    OutlinedNumberInput(
                        value = outputCompression,
                        onValueChange = vm::updateOutputCompression,
                        modifier = Modifier.width(120.dp),
                    )
                }
            }

            if (supportedResolutionValues.isNotEmpty()) {
                ImageOptionChips(
                    title = stringResource(R.string.imggen_page_resolution),
                    description = if (geminiModelId != null) {
                        stringResource(R.string.imggen_page_gemini_resolution_desc)
                    } else {
                        null
                    },
                    value = resolution,
                    options = supportedResolutionValues,
                    onValueChange = vm::updateResolution,
                )
            }

            if (supportedThinkingValues.isNotEmpty()) {
                ImageOptionChips(
                    title = stringResource(R.string.imggen_page_thinking),
                    description = stringResource(R.string.imggen_page_gemini_thinking_desc),
                    value = thinkingLevel,
                    options = supportedThinkingValues,
                    onValueChange = vm::updateThinkingLevel,
                )
            }

            if (geminiModelId != null && supportsGeminiTextResponse) {
                FormItem(
                    label = { Text(stringResource(R.string.imggen_page_gemini_text_response)) },
                    description = {
                        Text(stringResource(R.string.imggen_page_gemini_text_response_desc))
                    },
                    tail = {
                        Switch(
                            checked = geminiImageOptions.includeTextResponse,
                            onCheckedChange = vm::updateGeminiTextResponse,
                        )
                    },
                )
            }

            if (geminiModelId != null && supportsGeminiWebSearch) {
                FormItem(
                    label = { Text(stringResource(R.string.imggen_page_gemini_web_search)) },
                    description = {
                        Text(stringResource(R.string.imggen_page_gemini_web_search_desc))
                        Text(stringResource(R.string.imggen_page_gemini_search_warning))
                    },
                    tail = {
                        Switch(
                            checked = geminiImageOptions.webSearchGrounding,
                            onCheckedChange = vm::updateGeminiWebSearch,
                        )
                    },
                )
            }

            if (geminiModelId != null && supportsGeminiImageSearch) {
                FormItem(
                    label = { Text(stringResource(R.string.imggen_page_gemini_image_search)) },
                    description = {
                        Text(stringResource(R.string.imggen_page_gemini_image_search_desc))
                        Text(stringResource(R.string.imggen_page_gemini_search_warning))
                    },
                    tail = {
                        Switch(
                            checked = geminiImageOptions.imageSearchGrounding,
                            onCheckedChange = vm::updateGeminiImageSearch,
                        )
                    },
                )
            }

            if (geminiModelId != null && supportsGeminiSafetySettings) {
                val configuredSafetyCategories = geminiImageOptions.safetySettings.configuredCount()
                FormItem(
                    label = { Text(stringResource(R.string.assistant_gemini_safety_title)) },
                    description = {
                        Text(stringResource(R.string.imggen_page_gemini_safety_desc))
                    },
                    tail = {
                        TextButton(onClick = { showGeminiSafetySettings = true }) {
                            Text(
                                if (configuredSafetyCategories == 0) {
                                    stringResource(R.string.assistant_gemini_safety_default)
                                } else {
                                    stringResource(
                                        R.string.assistant_gemini_safety_summary,
                                        configuredSafetyCategories,
                                    )
                                }
                            )
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageOptionChips(
    title: String,
    description: String? = null,
    value: String?,
    options: Set<String>,
    onValueChange: (String?) -> Unit,
) {
    FormItem(
        label = { Text(title) },
        description = description?.let { text -> { Text(text) } },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = value == null,
                onClick = { onValueChange(null) },
                label = { Text(stringResource(R.string.imggen_page_model_default)) },
            )
            options.forEach { option ->
                val label = when (option) {
                    "off" -> stringResource(R.string.reasoning_off)
                    "low" -> stringResource(R.string.reasoning_light)
                    "medium" -> stringResource(R.string.reasoning_medium)
                    "high" -> stringResource(R.string.reasoning_heavy)
                    else -> option
                }
                FilterChip(
                    selected = value == option,
                    onClick = { onValueChange(option) },
                    label = { Text(label) },
                )
            }
        }
    }
}

private fun GeminiSafetySettings.configuredCount(): Int = listOf(
    harassment,
    hateSpeech,
    sexuallyExplicit,
    dangerousContent,
).count { it != GeminiSafetyThreshold.DEFAULT }

@Composable
private fun GeminiImageSafetyDialog(
    settings: GeminiSafetySettings,
    onSettingsChange: (GeminiSafetySettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assistant_gemini_safety_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.assistant_gemini_safety_detail))
                Text(
                    text = stringResource(R.string.assistant_gemini_safety_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                GeminiImageSafetyItem(
                    title = stringResource(R.string.assistant_gemini_safety_harassment),
                    description = stringResource(R.string.assistant_gemini_safety_harassment_desc),
                    selected = draft.harassment,
                    onSelected = { draft = draft.copy(harassment = it) },
                )
                GeminiImageSafetyItem(
                    title = stringResource(R.string.assistant_gemini_safety_hate),
                    description = stringResource(R.string.assistant_gemini_safety_hate_desc),
                    selected = draft.hateSpeech,
                    onSelected = { draft = draft.copy(hateSpeech = it) },
                )
                GeminiImageSafetyItem(
                    title = stringResource(R.string.assistant_gemini_safety_sexual),
                    description = stringResource(R.string.assistant_gemini_safety_sexual_desc),
                    selected = draft.sexuallyExplicit,
                    onSelected = { draft = draft.copy(sexuallyExplicit = it) },
                )
                GeminiImageSafetyItem(
                    title = stringResource(R.string.assistant_gemini_safety_dangerous),
                    description = stringResource(R.string.assistant_gemini_safety_dangerous_desc),
                    selected = draft.dangerousContent,
                    onSelected = { draft = draft.copy(dangerousContent = it) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSettingsChange(draft)
                onDismiss()
            }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun GeminiImageSafetyItem(
    title: String,
    description: String,
    selected: GeminiSafetyThreshold,
    onSelected: (GeminiSafetyThreshold) -> Unit,
) {
    FormItem(
        label = { Text(title) },
        description = { Text(description) },
    ) {
        Select(
            options = GeminiSafetyThreshold.entries,
            selectedOption = selected,
            onOptionSelected = onSelected,
            optionToString = { it.displayName() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GeminiImageParameterDialog(
    modelId: String,
    channel: ProviderRequestChannel,
    aspectRatio: String?,
    resolution: String?,
    thinkingLevel: String?,
    geminiImageOptions: GeminiImageGenerationOptions,
    referenceImageCount: Int,
    onDismiss: () -> Unit,
) {
    val apiDefault = stringResource(R.string.imggen_page_gemini_parameter_omitted)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.imggen_page_gemini_parameters_details)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.imggen_page_gemini_parameters_actual_desc))
                GeminiImageParameterItem(stringResource(R.string.log_page_model), modelId)
                GeminiImageParameterItem(
                    stringResource(R.string.log_page_channel),
                    channel.displayName(),
                )
                GeminiImageParameterItem("imageConfig.aspectRatio", aspectRatio ?: apiDefault)
                GeminiImageParameterItem("imageConfig.imageSize", resolution ?: apiDefault)
                GeminiImageParameterItem("thinkingConfig.thinkingLevel", thinkingLevel ?: apiDefault)
                GeminiImageParameterItem(
                    "responseModalities",
                    if (geminiImageOptions.includeTextResponse) "TEXT, IMAGE" else "IMAGE",
                )
                GeminiImageParameterItem(
                    "tools.googleSearch.webSearch",
                    geminiImageOptions.webSearchGrounding.localizedEnabledState(),
                )
                GeminiImageParameterItem(
                    "tools.googleSearch.imageSearch",
                    geminiImageOptions.imageSearchGrounding.localizedEnabledState(),
                )
                GeminiImageParameterItem(
                    stringResource(R.string.assistant_gemini_safety_title),
                    if (geminiImageOptions.safetySettings.configuredCount() == 0) {
                        stringResource(R.string.assistant_gemini_safety_default)
                    } else {
                        stringResource(
                            R.string.assistant_gemini_safety_summary,
                            geminiImageOptions.safetySettings.configuredCount(),
                        )
                    },
                )
                GeminiImageParameterItem(
                    stringResource(R.string.imggen_page_gemini_reference_images),
                    referenceImageCount.toString(),
                )
                Text(
                    text = stringResource(R.string.imggen_page_gemini_parameters_log_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.imggen_page_close))
            }
        },
    )
}

@Composable
private fun GeminiImageParameterItem(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ProviderRequestChannel.displayName(): String = stringResource(
    when (this) {
        ProviderRequestChannel.ANTHROPIC_API -> R.string.log_page_channel_anthropic_api
        ProviderRequestChannel.OPENAI_API -> R.string.log_page_channel_openai_api
        ProviderRequestChannel.XAI_API -> R.string.log_page_channel_xai_api
        ProviderRequestChannel.GOOGLE_AI_STUDIO -> R.string.log_page_channel_google_ai_studio
        ProviderRequestChannel.VERTEX_AI -> R.string.log_page_channel_vertex_ai
        ProviderRequestChannel.COMPATIBLE_ENDPOINT -> R.string.log_page_channel_compatible
    }
)

@Composable
private fun GeminiSafetyThreshold.displayName(): String = stringResource(
    when (this) {
        GeminiSafetyThreshold.DEFAULT -> R.string.assistant_gemini_safety_default
        GeminiSafetyThreshold.OFF -> R.string.assistant_gemini_safety_off
        GeminiSafetyThreshold.BLOCK_NONE -> R.string.assistant_gemini_safety_block_none
        GeminiSafetyThreshold.BLOCK_ONLY_HIGH -> R.string.assistant_gemini_safety_block_high
        GeminiSafetyThreshold.BLOCK_MEDIUM_AND_ABOVE -> R.string.assistant_gemini_safety_block_medium
        GeminiSafetyThreshold.BLOCK_LOW_AND_ABOVE -> R.string.assistant_gemini_safety_block_low
    }
)

@Composable
private fun Boolean.localizedEnabledState(): String = stringResource(
    if (this) R.string.imggen_page_gemini_enabled else R.string.imggen_page_gemini_disabled
)
