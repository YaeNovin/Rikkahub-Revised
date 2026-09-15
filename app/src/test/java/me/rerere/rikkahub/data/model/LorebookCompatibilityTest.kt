package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import me.rerere.rikkahub.data.ai.transformers.applyInjections
import me.rerere.rikkahub.data.ai.transformers.evaluateInjections
import me.rerere.rikkahub.data.export.*
import me.rerere.rikkahub.ui.pages.extensions.mergeLorebookEdits
import org.junit.Assert.*
import org.junit.Test

class LorebookCompatibilityTest {
    private fun imported(json: String, source: LorebookSourceFormat = LorebookSourceFormat.SILLY_TAVERN) =
        decodeTavernLorebook(Json.parseToJsonElement(json).jsonObject, "Book", source)
    private fun evaluate(book: Lorebook, history: List<UIMessage>, states: Map<kotlin.uuid.Uuid, LorebookEntryRuntimeState> = emptyMap(), entertainment: Boolean = true) =
        evaluateInjections(history, Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book), runtimeStates = states, entertainmentMode = entertainment)
    private fun entry(name: String = "scene") = PromptInjection.RegexInjection(name = name, content = "forest setting", keywords = listOf("forest"))

    @Test fun `source extensions and unknown fields survive native export and reimport`() {
        val book = imported("""{"extensions":{"vendor":{"custom":7}},"entries":{"42":{"content":"scene","key":["forest"],"position":4,"role":0,"depth":0,"sticky":0,"unknown":[1,2],"extensions":{"other":"kept"}}}}""")
        val restored = decodeLorebookDocument(LorebookSerializer.exportToJson(book), "B")
        assertEquals(book.sourceData, restored.sourceData)
        assertEquals(book.entries.single().sourceData, restored.entries.single().sourceData)
        assertEquals("42", restored.entries.single().sourceData!!.getValue("uid").jsonPrimitive.content)
        assertEquals(0, restored.entries.single().injectDepth)
        assertEquals(0, restored.entries.single().stickyTurns)
        assertEquals(MessageRole.SYSTEM, restored.entries.single().role)
    }

    @Test fun `string encoded keys and recursion guards are normalized without warnings`() {
        val book = imported("""
            {"entries":[{"key":"[\"forest\",\"/rain.+storm/i\"]","use_regex":true,"content":"scene","preventRecursion":true,"excludeRecursion":true}]}
        """.trimIndent())
        val rule = book.entries.single()
        assertTrue("actual keywords=${rule.keywords}", "forest" in rule.keywords && "/rain.+storm/i" in rule.keywords)
        assertTrue(rule.preventRecursion)
        assertTrue(rule.excludeRecursion)
        assertTrue(book.importWarnings.none { it.contains("preventRecursion") || it.contains("excludeRecursion") })
        assertTrue(rule.evaluateKeywords("RAIN and STORM").matched)
    }

    @Test fun `imported key arrays do not leak JSON wrappers or control characters`() {
        val book = imported("""
            {"entries":[{"key":"[\"alpha\",\"beta\\u0001\"]","keysecondary":"[\"gamma\"]","selective":true,"content":"scene"}]}
        """.trimIndent())
        val rule = book.entries.single()
        assertEquals(listOf("alpha", "beta"), rule.keywords)
        assertFalse(rule.keywordExpression.contains("["))
        assertFalse(rule.keywordExpression.contains("]"))
        assertTrue(rule.keywordExpression.contains("gamma"))
    }

    @Test fun `standalone V3 wrapper and embedded cards use their source semantics`() {
        val book = decodeLorebookDocument("""{"spec":"lorebook_v3","data":{"entries":[{"keys":["cat"],"use_regex":true,"constant":true,"content":"scene"}]}}""", "Book")
        assertEquals(LorebookSourceFormat.CHARACTER_CARD_V3, book.sourceFormat)
        assertFalse(book.entries.single().constantActive)
        val card = decodeLorebookDocument("""{"spec":"chara_card_v2","data":{"character_book":{"entries":[]}}}""", "Book")
        assertEquals(LorebookSourceFormat.CHARACTER_CARD_V2, card.sourceFormat)
    }

    @Test fun `author note example and outlet positions are mapped explicitly`() {
        for (position in listOf(2, 3, 5, 6, 7, 99)) {
            val book = imported("""{"entries":[{"constant":true,"content":"secret scene","position":$position}]}""")
            if (position == 99) {
                assertEquals(position.toString(), book.entries.single().unsupportedPosition)
                assertTrue(evaluate(book, listOf(UIMessage.user("x"))).injections.isEmpty())
            } else {
                assertNull(book.entries.single().unsupportedPosition)
                if (position == 7) assertTrue(evaluate(book, listOf(UIMessage.user("x"))).injections.isEmpty())
                else assertEquals(1, evaluate(book, listOf(UIMessage.user("x"))).injections.size)
            }
        }
    }

    @Test fun `missing conditions and disabled entries are preserved with reports`() {
        val book = imported("""{"entries":[{"content":"x","vectorized":true},{"content":"","disable":true}]}""")
        assertEquals(2, book.entries.size)
        assertFalse(book.entries[1].enabled)
        assertTrue(book.importWarnings.isNotEmpty())
        assertTrue(evaluate(book, listOf(UIMessage.user("x"))).injections.isEmpty())
    }

    @Test fun `budget priority and ascending insertion order are independent`() {
        val book = imported("""{"entries":[{"constant":true,"content":"late","priority":90,"insertion_order":200,"position":1},{"constant":true,"content":"early","priority":1,"insertion_order":10,"position":1}]}""", LorebookSourceFormat.CHARACTER_CARD_V2)
        val all = evaluate(book, listOf(UIMessage.user("x")))
        assertEquals(listOf("late", "early"), all.injections.map { it.content })
        val output = applyInjections(listOf(UIMessage.user("x")), all.injections.orderedForInsertion().groupBy { it.position })
        assertEquals("early\nlate", output.first().toText())
        val limited = evaluate(book.copy(tokenBudget = estimateTextTokens("late")), listOf(UIMessage.user("x")))
        assertEquals(listOf("late"), limited.injections.map { it.content })
    }

    @Test fun `depth anchors do not count other inserted messages and zero appends`() {
        val history = listOf(UIMessage.user("u1"), UIMessage.assistant("a1"), UIMessage.user("u2"))
        val deep = entry().copy(position = InjectionPosition.AT_DEPTH, injectDepth = 3, content = "deep", role = MessageRole.SYSTEM)
        val shallow = deep.copy(id = kotlin.uuid.Uuid.random(), injectDepth = 1, content = "shallow")
        val last = deep.copy(id = kotlin.uuid.Uuid.random(), injectDepth = 0, content = "last")
        val top = deep.copy(id = kotlin.uuid.Uuid.random(), position = InjectionPosition.TOP_OF_CHAT, content = "top")
        val output = applyInjections(history, listOf(deep, shallow, last, top).groupBy { it.position })
        assertEquals(listOf("top\ndeep", "u1", "a1", "shallow", "u2", "last"), output.map { it.toText() })
        assertEquals(MessageRole.SYSTEM, output.last().role)
    }

    @Test fun `message timed effects expire at exclusive end while native turns stay unchanged`() {
        val book = imported("""{"entries":[{"key":["forest"],"content":"scene","scanDepth":1,"sticky":3,"cooldown":2}]}""")
        val history = mutableListOf(UIMessage.user("forest"))
        val first = evaluate(book, history)
        history += UIMessage.assistant("plain")
        history += UIMessage.user("plain")
        val sticky = evaluate(book, history, first.runtimeStates)
        assertEquals(LorebookEntryStatus.ACTIVE_FROM_PREVIOUS_TURN, sticky.diagnostics.entries.single().status)
        history += UIMessage.assistant("plain")
        history += UIMessage.user("forest")
        assertEquals(LorebookEntryStatus.COOLDOWN, evaluate(book, history, first.runtimeStates).diagnostics.entries.single().status)
        history += UIMessage.assistant("plain")
        history += UIMessage.user("forest")
        assertEquals(LorebookEntryStatus.USED, evaluate(book, history, first.runtimeStates).diagnostics.entries.single().status)
    }

    @Test fun `regeneration does not turn a nonsticky hit into cooldown refusal`() {
        val book = imported("""{"entries":[{"key":["forest"],"content":"scene","sticky":0,"cooldown":5}]}""")
        val history = listOf(UIMessage.user("forest"))
        val first = evaluate(book, history)
        assertEquals(LorebookEntryStatus.USED, evaluate(book, history, first.runtimeStates).diagnostics.entries.single().status)
    }

    @Test fun `normal mode does not discard imported selective conditions or probability`() {
        val book = imported("""{"entries":[{"key":["forest"],"keysecondary":["rain"],"selective":true,"content":"scene"}]}""")
        assertTrue(evaluate(book, listOf(UIMessage.user("forest")), entertainment = false).injections.isEmpty())
        assertEquals(1, evaluate(book, listOf(UIMessage.user("forest rain")), entertainment = false).injections.size)
    }

    @Test fun `native event delay remains dormant in ordinary mode`() {
        val book = Lorebook(entries = listOf(entry().copy(constantActive = true, delayMessages = 100)))
        assertEquals(1, evaluate(book, listOf(UIMessage.user("x")), entertainment = false).injections.size)
        assertTrue(evaluate(book, listOf(UIMessage.user("x")), entertainment = true).injections.isEmpty())
    }

    @Test fun `delay uses full history while message scanning uses selected tail`() {
        val book = imported("""{"entries":[{"constant":true,"content":"scene","delay":3}]}""")
        assertTrue(evaluate(book, listOf(UIMessage.user("x"))).injections.isEmpty())
        assertEquals(1, evaluate(book, listOf(UIMessage.user("x"), UIMessage.assistant("y"), UIMessage.user("z"))).injections.size)
    }

    @Test fun `mixed keyword literals and regex flags match without global regex mode`() {
        val rule = entry().copy(sourceFormat = LorebookSourceFormat.SILLY_TAVERN, keywords = listOf("/rain.+storm/i", "forest"))
        assertTrue(rule.evaluateKeywords("RAIN and STORM").matched)
        assertTrue(rule.evaluateKeywords("forest").matched)
        assertFalse(rule.evaluateKeywords("rain").matched)
        assertNotNull(rule.copy(keywords = listOf("/rain/y")).evaluateKeywords("rain").error)
        assertTrue(rule.copy(keywords = listOf("{{char}}" )).evaluateKeywords("Mage", resolveTerm = { it.replace("{{char}}", "Mage") }).matched)
    }

    @Test fun `regex literal parser keeps escaped slash and character class slash`() {
        val escaped = entry().copy(sourceFormat = LorebookSourceFormat.SILLY_TAVERN, keywords = listOf("/foo\\/bar/i"))
        assertTrue(escaped.evaluateKeywords("FOO/BAR").matched)
        val characterClass = entry().copy(sourceFormat = LorebookSourceFormat.SILLY_TAVERN, keywords = listOf("/[a/b]+/"))
        assertTrue(characterClass.evaluateKeywords("a/b").matched)
    }

    @Test fun `unsupported javascript regex flags produce actionable diagnostics`() {
        val rule = entry().copy(sourceFormat = LorebookSourceFormat.SILLY_TAVERN, keywords = listOf("/rain/y"))
        val result = rule.evaluateKeywords("rain")
        assertFalse(result.matched)
        assertTrue(result.error.orEmpty().contains("y/d/v"))
        assertTrue(entry().copy(sourceFormat = LorebookSourceFormat.SILLY_TAVERN, keywords = listOf("/rain/g")).evaluateKeywords("rain").matched)
    }

    @Test fun `legacy string keys split terms without splitting regex literals`() {
        val book = imported("""{"entries":[{"key":"alpha beta /foo,bar/i \"quoted phrase\"","use_regex":true,"content":"scene"}]}""")
        val terms = book.entries.single().keywords
        assertTrue("alpha" in terms && "beta" in terms)
        assertTrue("/foo,bar/i" in terms)
        assertTrue("quoted phrase" in terms)
        assertTrue(book.entries.single().evaluateKeywords("FOO,BAR").matched)
    }

    @Test fun `array key phrases remain a single term`() {
        val book = imported("""{"entries":[{"key":["alpha beta","/foo bar/i"],"content":"scene"}]}""")
        assertEquals(listOf("alpha beta", "/foo bar/i"), book.entries.single().keywords)
        assertTrue(book.entries.single().evaluateKeywords("say alpha beta").matched)
    }

    @Test fun `whole word matching avoids partial English words`() {
        val rule = entry().copy(keywords = listOf("cat"), matchWholeWords = true)
        assertFalse(rule.evaluateKeywords("catalog").matched)
        assertTrue(rule.evaluateKeywords("a cat!").matched)
    }

    @Test fun `V3 with regex disabled treats slash keys as plain text`() {
        val book = imported("""{"entries":[{"keys":["/rain/i"],"use_regex":false,"content":"scene"}]}""", LorebookSourceFormat.CHARACTER_CARD_V3)
        assertFalse(book.entries.single().evaluateKeywords("RAIN").matched)
        assertTrue(book.entries.single().evaluateKeywords("/rain/i").matched)
    }

    @Test fun `edited imported content is not leaked through source metadata`() {
        val book = imported("""{"entries":[{"constant":true,"content":"original private scene","extensions":{"vendor":42}}]}""")
        val edited = book.copy(entries = book.entries.map { it.copy(content = "new scene") })
        val exported = LorebookSerializer.exportToJson(edited)
        assertFalse(exported.contains("original private scene"))
        assertTrue(exported.contains("vendor"))
    }

    @Test fun `pathological regex stops with diagnostic rather than stalling generation`() {
        val rule = entry().copy(useRegex = true, keywords = listOf("(a+)+$"))
        val result = rule.evaluateKeywords("a".repeat(100) + "!", budget = LorebookMatchBudget(500))
        assertFalse(result.matched)
        assertTrue(result.error.orEmpty().contains("上限"))
    }

    @Test fun `V3 regex ignores constant and secondary conditions and strips decorators`() {
        val book = imported("""{"entries":[{"keys":["forest"],"use_regex":true,"constant":true,"selective":true,"secondary_keys":["rain"],"content":"@@unknown foo\nscene","position":"after_char"}]}""", LorebookSourceFormat.CHARACTER_CARD_V3)
        assertTrue(evaluate(book, listOf(UIMessage.user("plain"))).injections.isEmpty())
        assertEquals("scene", evaluate(book, listOf(UIMessage.user("forest"))).injections.single().content)
        assertTrue(book.entries.single().content.startsWith("@@"))
        assertFalse(book.entries.single().sourceData!!.containsKey("content"))
    }

    @Test fun `expanded placeholders are counted before budget selection`() {
        val book = Lorebook(entries = listOf(entry().copy(constantActive = true, content = "{char_name}")), tokenBudget = 3)
        val evaluation = evaluateInjections(listOf(UIMessage.user("x")), Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book), entertainmentMode = true,
            resolveContent = { it.replace("{char_name}", "name ".repeat(100)) })
        assertTrue(evaluation.injections.isEmpty())
    }

    @Test fun `truncation does not leave broken surrogate or placeholder`() {
        val content = "prefix {{char_name}} tail" + "🌏".repeat(100)
        for (budget in 1..25) {
            val truncated = trimToEstimatedTokens(content, budget) { it.replace("{{char_name}}", "name ".repeat(20)) }
            assertFalse(truncated.endsWith("{"))
            assertFalse(truncated.lastOrNull()?.isHighSurrogate() == true)
            assertTrue(estimateTextTokens(truncated.replace("{{char_name}}", "name ".repeat(20))) <= budget)
        }
    }

    @Test fun `duplicate names are never collapsed and ambiguous replacement is rejected`() {
        val old = Lorebook(entries = listOf(entry()))
        val first = entry(); val second = entry()
        val incoming = Lorebook(entries = listOf(first, second))
        assertEquals(3, mergeImportedLorebook(old, incoming, emptyMap()).entries.size)
        assertTrue(runCatching { mergeImportedLorebook(old, incoming, mapOf(first.id to LorebookMergeChoice.USE_IMPORTED)) }.isFailure)
    }

    @Test fun `merge retains target identity and inherited source scan depth`() {
        val old = Lorebook(defaultScanDepth = 8, entries = listOf(entry()))
        val new = entry().copy(content = "updated", scanMode = LorebookScanMode.INHERIT)
        val incoming = Lorebook(defaultScanDepth = 0, entries = listOf(new))
        assertEquals("forest setting", mergeImportedLorebook(old, incoming, emptyMap()).entries.single().content)
        val merged = mergeImportedLorebook(old, incoming, mapOf(new.id to LorebookMergeChoice.USE_IMPORTED))
        assertEquals(old.entries.single().id, merged.entries.single().id)
        assertEquals(8, merged.defaultScanDepth)
        assertEquals(0, merged.entries.single().scanDepth)
        assertEquals(LorebookScanMode.CUSTOM, merged.entries.single().scanMode)
    }

    @Test fun `concurrent edit or deletion of same entry is reported not overwritten`() {
        val book = Lorebook(entries = listOf(entry()))
        val changed = book.copy(entries = book.entries.map { it.copy(content = "my edit") })
        val other = book.copy(entries = book.entries.map { it.copy(content = "other edit") })
        assertTrue(runCatching { mergeLorebookEdits(book, changed, other) }.isFailure)
        assertTrue(runCatching { mergeLorebookEdits(book, changed, book.copy(entries = emptyList())) }.isFailure)
        assertEquals(changed.entries, mergeLorebookEdits(book, changed, changed).entries)
    }

    @Test fun `stored native sticky state from before compatibility fields remains valid`() {
        val rule = entry().copy(stickyTurns = 4, scanDepth = 1)
        val json = me.rerere.rikkahub.utils.JsonInstant
        val fields = json.encodeToJsonElement(PromptInjection.RegexInjection.serializer(), rule).jsonObject
        val legacy = JsonObject(fields - setOf("insertionOrder", "timingUnit", "delayMessages", "matchWholeWords", "sourceFormat", "sourceData", "unsupportedPosition"))
        val legacyFingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(json.encodeToString(JsonObject.serializer(), legacy).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val book = Lorebook(entries = listOf(rule))
        val firstMessage = UIMessage.user("forest")
        val first = evaluate(book, listOf(firstMessage))
        val persisted = first.runtimeStates.mapValues { it.value.copy(ruleFingerprint = legacyFingerprint) }
        val next = evaluate(book, listOf(firstMessage, UIMessage.user("plain")), persisted)
        assertEquals(LorebookEntryStatus.ACTIVE_FROM_PREVIOUS_TURN, next.diagnostics.entries.single().status)
    }

    @Test fun `recursive scanning activates entries from injected content`() {
        val first = entry("forest").copy(content = "The castle is nearby", keywords = listOf("forest"))
        val second = entry("castle").copy(content = "Castle rules", keywords = listOf("castle"))
        val book = Lorebook(entries = listOf(first, second), recursiveScanning = true, maxRecursionSteps = 2)
        val result = evaluate(book, listOf(UIMessage.user("forest")))
        assertEquals(listOf("The castle is nearby", "Castle rules"), result.injections.map { it.content })
        assertEquals(1, result.diagnostics.entries.last { it.entryId == second.id }.recursionLevel)
    }

    @Test fun `delay and prevent recursion flags follow SillyTavern semantics`() {
        val first = entry("forest").copy(content = "castle", keywords = listOf("forest"), preventRecursion = true)
        val delayed = entry("castle").copy(content = "delayed", keywords = listOf("castle"), delayUntilRecursion = true)
        val book = Lorebook(entries = listOf(first, delayed), recursiveScanning = true, maxRecursionSteps = 2)
        assertEquals(1, evaluate(book, listOf(UIMessage.user("forest"))).injections.size)
        val source = first.copy(preventRecursion = false)
        val result = evaluate(book.copy(entries = listOf(source, delayed)), listOf(UIMessage.user("forest")))
        assertEquals(listOf("castle", "delayed"), result.injections.map { it.content })
    }

    @Test fun `group scoring chooses the most specific matching entry`() {
        val broad = entry("broad").copy(keywords = listOf("forest"), exclusiveGroup = "scene", selectionWeight = 100, useGroupScoring = true)
        val specific = entry("specific").copy(keywords = listOf("forest", "castle"), exclusiveGroup = "scene", selectionWeight = 100)
        val result = evaluate(Lorebook(entries = listOf(broad, specific)), listOf(UIMessage.user("forest castle")))
        assertEquals(specific.id, result.injections.single().id)
    }

    @Test fun `outlet entries are only inserted when their macro is present`() {
        val outlet = entry("facts").copy(constantActive = true, position = InjectionPosition.OUTLET, outletName = "Facts", content = "Hidden facts")
        val book = Lorebook(entries = listOf(outlet))
        val noMacroEvaluation = evaluate(book, listOf(UIMessage.user("x")))
        assertTrue(noMacroEvaluation.injections.isEmpty())
        val evaluation = evaluate(book, listOf(UIMessage.system("{{outlet::Facts}}"), UIMessage.user("x")))
        assertEquals(1, evaluation.injections.size)
        val noMacro = applyInjections(listOf(UIMessage.system("No outlet")), evaluation.injections.groupBy { it.position })
        assertEquals("No outlet", noMacro.single().toText())
        val withMacro = applyInjections(listOf(UIMessage.system("{{outlet::Facts}}")), evaluation.injections.groupBy { it.position })
        assertEquals("Hidden facts", withMacro.single().toText())
    }

    @Test fun `generation triggers and character filters gate activation`() {
        val rule = entry().copy(
            generationTriggers = setOf(LorebookGenerationTrigger.REGENERATE),
            characterFilter = listOf("Alice"),
        )
        val book = Lorebook(entries = listOf(rule))
        fun run(trigger: LorebookGenerationTrigger, name: String) = evaluateInjections(
            listOf(UIMessage.user("forest")), Assistant(name = name, lorebookIds = setOf(book.id)), emptyList(), listOf(book),
            entertainmentMode = true, generationTrigger = trigger,
        )
        assertTrue(run(LorebookGenerationTrigger.NORMAL, "Alice").injections.isEmpty())
        assertTrue(run(LorebookGenerationTrigger.REGENERATE, "Bob").injections.isEmpty())
        assertEquals(1, run(LorebookGenerationTrigger.REGENERATE, "Alice").injections.size)
    }

    @Test fun `v3 decorators control activation position role and depth`() {
        val rule = PromptInjection.RegexInjection(
            name = "decorated", content = "@@activate\n@@position before_desc\n@@role system\n@@depth 2\nDecorated body",
            sourceFormat = LorebookSourceFormat.CHARACTER_CARD_V3,
        )
        val book = Lorebook(entries = listOf(rule))
        val result = evaluate(book, listOf(UIMessage.user("plain")))
        assertEquals(1, result.injections.size)
        assertEquals("Decorated body", result.injections.single().content)
        assertEquals(InjectionPosition.BEFORE_SYSTEM_PROMPT, result.injections.single().position)
        assertEquals(MessageRole.SYSTEM, result.injections.single().role)
        assertEquals(2, result.injections.single().injectDepth)
    }

    @Test fun `v3 periodic and dont activate decorators are honored`() {
        val periodic = entry("periodic").copy(sourceFormat = LorebookSourceFormat.CHARACTER_CARD_V3, keywords = listOf("x"), scanDepth = 1, content = "@@activate_only_every 2\nbody")
        val disabled = entry("disabled").copy(sourceFormat = LorebookSourceFormat.CHARACTER_CARD_V3, keywords = listOf("x"), scanDepth = 1, content = "@@dont_activate\nbody")
        val book = Lorebook(entries = listOf(periodic, disabled))
        // V3 counts assistant messages, and periodic gating never replaces keyword matching.
        assertTrue(evaluate(book, listOf(UIMessage.user("x"), UIMessage.assistant("x"))).injections.isEmpty())
        assertEquals(1, evaluate(book, listOf(UIMessage.user("x"), UIMessage.assistant("y"), UIMessage.user("plain"), UIMessage.assistant("x"))).injections.size)
    }

    @Test fun `multiple inclusion groups select one winning candidate`() {
        val first = entry("first").copy(inclusionGroups = listOf("a", "b"), exclusiveGroup = "a", selectionWeight = 100)
        val second = entry("second").copy(inclusionGroups = listOf("a"), exclusiveGroup = "a", selectionWeight = 100, priority = 10)
        val book = Lorebook(entries = listOf(first, second))
        val result = evaluate(book, listOf(UIMessage.user("forest")))
        assertEquals(1, result.injections.size)
    }

    @Test fun `lorebook source scopes are ordered chat persona character global`() {
        val chat = Lorebook(name = "chat", sourceScope = LorebookSourceScope.CHAT, entries = listOf(entry("chat").copy(constantActive = true, content = "chat")))
        val global = Lorebook(name = "global", sourceScope = LorebookSourceScope.GLOBAL, entries = listOf(entry("global").copy(constantActive = true, content = "global")))
        val assistant = Assistant(lorebookIds = setOf(global.id, chat.id), allowConversationPromptInjection = true)
        val result = evaluateInjections(listOf(UIMessage.user("x")), assistant, emptyList(), listOf(global, chat), conversationLorebookIds = setOf(chat.id), entertainmentMode = true)
        assertEquals(listOf("chat", "global"), result.injections.map { it.content })
    }

    @Test fun `ignore budget entry remains available after regular budget is exhausted`() {
        val regular = entry("regular").copy(content = "regular content", priority = 10)
        val ignored = entry("ignored").copy(content = "ignored content", priority = 1, ignoreBudget = true)
        val book = Lorebook(entries = listOf(regular, ignored), tokenBudget = 1, overflowStrategy = LorebookOverflowStrategy.DROP_LOW_PRIORITY)
        val result = evaluate(book, listOf(UIMessage.user("forest")))
        assertTrue(result.injections.any { it.id == ignored.id })
    }
}
