package me.rerere.ai.provider

/**
 * Infers the primary model role from identifiers used by official and compatible APIs.
 * Unknown identifiers stay as chat models so users can still configure them manually.
 */
fun inferModelTypeFromId(modelId: String): ModelType {
    val id = modelId.substringAfterLast('/').trim().lowercase()
    if (id.isEmpty()) return ModelType.CHAT

    val tokens = id.split(MODEL_TOKEN_SEPARATOR).filter(String::isNotEmpty).toSet()
    if (
        id.contains("embedding") ||
        id.contains("embed") ||
        EMBEDDING_MODEL_TOKENS.any(tokens::contains) ||
        id.startsWith("bge-") ||
        id.startsWith("e5-") ||
        id.startsWith("gte-") ||
        id.contains("mxbai") ||
        id.contains("nomic-embed") ||
        id.contains("jina-embeddings")
    ) {
        return ModelType.EMBEDDING
    }

    val isGeminiImage = id.startsWith("gemini-") && IMAGE_TOKEN.containsMatchIn(id)
    val isGrokImage = id.startsWith("grok-") && IMAGE_TOKEN.containsMatchIn(id)
    if (
        isGeminiImage ||
        isGrokImage ||
        IMAGE_MODEL_MARKERS.any(id::contains)
    ) {
        return ModelType.IMAGE
    }

    return ModelType.CHAT
}

private val MODEL_TOKEN_SEPARATOR = Regex("[-_./]+")
private val IMAGE_TOKEN = Regex("(?:^|[-_./])image(?:$|[-_./])")
private val EMBEDDING_MODEL_TOKENS = setOf("bge", "e5", "gte")
private val IMAGE_MODEL_MARKERS = listOf(
    "gpt-image",
    "dall-e",
    "imagen",
    "image-generation",
    "imagegeneration",
    "nano-banana",
    "nanobanana",
    "flux",
    "stable-diffusion",
    "stable_image",
    "sdxl",
    "seedream",
    "jimeng",
    "dreamina",
    "cogview",
    "kolors",
    "qwen-image",
    "wanx",
    "hunyuan-image",
    "hidream",
    "z-image",
    "midjourney",
    "ideogram",
    "recraft",
    "kandinsky",
)
