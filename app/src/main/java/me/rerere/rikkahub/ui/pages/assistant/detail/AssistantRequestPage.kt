package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import me.rerere.rikkahub.ui.components.ui.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.ai.provider.parameterModelId
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantRequestPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val model = settings.providers.findModelById(assistant.chatModelId ?: settings.chatModelId)
    val provider = model?.findProvider(settings.providers)
    val route = provider?.parameterRequestRouteForModel(model.parameterModelId())
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_request))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.scaffoldContainerColor,
    ) { innerPadding ->
        AssistantRequestContent(
            innerPadding = innerPadding,
            assistant = assistant,
            modelId = model?.parameterModelId(),
            route = route,
            modelHeaders = model?.customHeaders.orEmpty(),
            modelBodies = model?.customBodies.orEmpty(),
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
internal fun AssistantRequestContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    modelId: String?,
    route: ParameterRequestRoute?,
    modelHeaders: List<me.rerere.ai.provider.CustomHeader>,
    modelBodies: List<me.rerere.ai.provider.CustomBody>,
    onUpdate: (Assistant) -> Unit
) {
    val issues = analyzeCustomRequest(
        assistantHeaders = assistant.customHeaders,
        assistantBodies = assistant.customBodies,
        modelHeaders = modelHeaders,
        modelBodies = modelBodies,
        route = route,
    )
    val presets = customRequestPresets(route, modelId.orEmpty())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CustomRequestOverview(
            modelId = modelId,
            route = route,
            issues = issues,
            presets = presets,
            existingBodyKeys = assistant.customBodies.mapTo(hashSetOf()) { it.key.trim() },
            effectiveHeaders = effectiveCustomHeaders(assistant.customHeaders, modelHeaders),
            effectiveBody = effectiveCustomBody(assistant.customBodies, modelBodies),
            onImportBody = { imported ->
                onUpdate(
                    assistant.copy(
                        customBodies = importCustomBodyObject(assistant.customBodies, imported)
                    )
                )
            },
            onApplyPreset = { preset ->
                onUpdate(
                    assistant.copy(
                        customBodies = addCustomRequestPreset(assistant.customBodies, preset)
                    )
                )
            },
        )

        CustomHeaders(
            headers = assistant.customHeaders,
            onUpdate = {
                onUpdate(
                    assistant.copy(
                        customHeaders = it
                    )
                )
            }
        )

        HorizontalDivider()

        CustomBodies(
            customBodies = assistant.customBodies,
            onUpdate = {
                onUpdate(
                    assistant.copy(
                        customBodies = it
                    )
                )
            }
        )
    }
}
