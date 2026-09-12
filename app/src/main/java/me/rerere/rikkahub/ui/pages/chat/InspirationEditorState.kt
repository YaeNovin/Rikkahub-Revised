package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import me.rerere.rikkahub.data.model.InspirationCard
import me.rerere.rikkahub.data.model.InspirationSettings

/** Keep potentially large custom prompts out of the saved-instance-state Bundle. */
internal class InspirationEditorState : ViewModel() {
    private var session: String? = null
    var draft by mutableStateOf(InspirationSettings())
    var inherited by mutableStateOf(false)
    var editing by mutableStateOf<InspirationCard?>(null)
    var deleting by mutableStateOf<InspirationCard?>(null)

    fun begin(key: String, initial: InspirationSettings, inherit: Boolean) {
        if (session == key) return
        session = key
        draft = initial
        inherited = inherit
        editing = null
        deleting = null
    }
}
