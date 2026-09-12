package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationMemoryMode
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.data.datastore.resolveMemoryExtractionModel

@Composable
internal fun ConversationMemoryControls(
    conversation: Conversation,
    onChange: (ConversationMemoryMode) -> Unit,
) {
    val extractionModel = LocalSettings.current.resolveMemoryExtractionModel()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.conversation_memory_title))
        Select(
            options = ConversationMemoryMode.entries,
            selectedOption = conversation.memoryMode,
            enabled = conversation.sourceConversationId == null,
            onOptionSelected = onChange,
            optionEnabled = { it != ConversationMemoryMode.EXTRACTION_ONLY || extractionModel != null },
            optionToString = { mode ->
                stringResource(when (mode) {
                    ConversationMemoryMode.DISABLED -> R.string.conversation_memory_disabled
                    ConversationMemoryMode.ENABLED -> R.string.conversation_memory_enabled
                    ConversationMemoryMode.RAG_ONLY -> R.string.conversation_memory_rag
                    ConversationMemoryMode.INHERIT -> R.string.conversation_memory_inherit
                    ConversationMemoryMode.EXTRACTION_ONLY -> R.string.conversation_memory_extraction_only
                })
            },
        )
        Text(stringResource(
            if (conversation.sourceConversationId != null) R.string.conversation_memory_branch_desc
            else R.string.conversation_memory_scope_desc
        ))
        if (conversation.memoryMode == ConversationMemoryMode.EXTRACTION_ONLY) {
            Text(stringResource(R.string.conversation_memory_extraction_only_desc), style = MaterialTheme.typography.bodySmall)
        }
        if (extractionModel == null) {
            Text(stringResource(R.string.conversation_memory_extraction_model_required),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else if (conversation.memoryMode == ConversationMemoryMode.EXTRACTION_ONLY) {
            Text(stringResource(R.string.conversation_memory_extraction_model_name, extractionModel.displayName.ifBlank { extractionModel.modelId }),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
