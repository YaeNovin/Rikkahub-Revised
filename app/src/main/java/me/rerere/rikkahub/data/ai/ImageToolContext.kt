package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.CHAT_IMAGE_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.INSPECT_GENERATED_IMAGE_TOOL_NAME

/** Generation is text-only on the wire; only an explicit inspection exposes pixels to vision. */
internal fun List<UIMessage>.imageToolContextFor(model: Model, inspectionAvailable: Boolean = false): List<UIMessage> {
    val vision = Modality.IMAGE in model.inputModalities
    return map { message -> message.copy(parts = message.parts.map { part ->
        if (part is UIMessagePart.Tool && (part.toolName == CHAT_IMAGE_TOOL_NAME ||
                !vision && part.toolName == INSPECT_GENERATED_IMAGE_TOOL_NAME)) {
            part.copy(output = part.output.map { output ->
                if (output is UIMessagePart.Image) UIMessagePart.Text(when {
                    vision && inspectionAvailable -> "[Image displayed. Optionally call inspect_generated_image with task_id to view it.]"
                    vision -> "[Generated image displayed in the conversation; no image inspection tool is enabled.]"
                    else -> "[Generated image saved in the conversation; this model cannot inspect image content.]"
                })
                else output
            })
        } else part
    }) }
}
