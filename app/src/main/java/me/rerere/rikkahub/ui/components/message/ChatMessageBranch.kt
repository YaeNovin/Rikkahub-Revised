package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MessageNode

@Composable
fun ChatMessageBranchSelector(
    node: MessageNode,
    modifier: Modifier = Modifier,
    onUpdate: (MessageNode) -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (node.messages.size > 1) {
            val actionColor = MaterialTheme.colorScheme.onSurfaceVariant
            val iconButtonColors = IconButtonDefaults.iconButtonColors(
                contentColor = actionColor,
                disabledContentColor = actionColor.copy(alpha = 0.38f),
            )

            IconButton(
                onClick = {
                    onUpdate(node.copy(selectIndex = node.selectIndex - 1))
                },
                enabled = node.selectIndex > 0,
                modifier = Modifier
                    .size(40.dp),
                colors = iconButtonColors,
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowLeft01,
                    contentDescription = stringResource(R.string.chat_message_previous_branch),
                    modifier = Modifier.size(16.dp),
                )
            }

            Text(
                text = "${node.selectIndex + 1}/${node.messages.size}",
                style = MaterialTheme.typography.bodySmall,
                color = actionColor
            )

            IconButton(
                onClick = {
                    onUpdate(node.copy(selectIndex = node.selectIndex + 1))
                },
                enabled = node.selectIndex < node.messages.lastIndex,
                modifier = Modifier
                    .size(40.dp),
                colors = iconButtonColors,
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = stringResource(R.string.chat_message_next_branch),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
