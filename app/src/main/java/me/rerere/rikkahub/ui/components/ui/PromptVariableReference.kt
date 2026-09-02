package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.transformers.PromptVariableCatalog
import me.rerere.rikkahub.data.ai.transformers.PromptVariableDescriptor
import me.rerere.rikkahub.data.ai.transformers.PromptVariableScope
import me.rerere.rikkahub.ui.theme.JetbrainsMono

/** A compact, shared reference for the variables supported by a prompt field. */
@Composable
fun PromptVariableReference(
    scope: PromptVariableScope,
    modifier: Modifier = Modifier,
    onInsert: ((String) -> Unit)? = null,
    showDescriptions: Boolean = true,
) {
    val variables = PromptVariableCatalog.primaryForScope(scope)
    var descriptionsExpanded by remember(scope) { mutableStateOf(false) }
    if (variables.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.prompt_variable_reference_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            if (showDescriptions) {
                androidx.compose.material3.TextButton(
                    onClick = { descriptionsExpanded = !descriptionsExpanded },
                ) {
                    Text(
                        stringResource(
                            if (descriptionsExpanded) {
                                R.string.prompt_variable_hide_details
                            } else {
                                R.string.prompt_variable_show_details
                            }
                        )
                    )
                }
            }
        }
        Text(
            text = stringResource(
                if (onInsert == null) {
                    R.string.prompt_variable_reference_manual_hint
                } else {
                    R.string.prompt_variable_reference_insert_hint
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            variables.forEach { variable ->
                Tag(onClick = onInsert?.let { insert -> { insert(variable.token) } }) {
                    Text(stringResource(variable.labelRes))
                    Text(": ${variable.token}", fontFamily = JetbrainsMono)
                }
            }
        }
        if (showDescriptions && descriptionsExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                variables.forEach { variable ->
                    PromptVariableDescription(
                        variable = variable,
                        aliases = PromptVariableCatalog.aliasesFor(variable, scope),
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptVariableDescription(
    variable: PromptVariableDescriptor,
    aliases: List<PromptVariableDescriptor>,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = variable.token,
            modifier = Modifier.padding(top = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(variable.labelRes),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = stringResource(variable.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (aliases.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.prompt_variable_compatibility_aliases,
                        aliases.joinToString(", ") { it.token },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
