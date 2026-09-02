package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant

class AdvancedAppearanceSettingTest {
    @Test
    fun `global background is disabled by default`() {
        assertFalse(Settings().isGlobalBackgroundActive())
    }

    @Test
    fun `global background requires a selected image`() {
        val settings = Settings(
            advancedAppearanceSetting = AdvancedAppearanceSetting(
                enableGlobalBackground = true,
                globalBackground = null,
            )
        )

        assertFalse(settings.isGlobalBackgroundActive())
    }

    @Test
    fun `blank global background remains inactive`() {
        val settings = Settings(
            advancedAppearanceSetting = AdvancedAppearanceSetting(
                enableGlobalBackground = true,
                globalBackground = "   ",
            )
        )

        assertFalse(settings.isGlobalBackgroundActive())
    }

    @Test
    fun `global background activates when enabled with an image`() {
        val settings = Settings(
            advancedAppearanceSetting = AdvancedAppearanceSetting(
                enableGlobalBackground = true,
                globalBackground = "file:///backgrounds/example.jpg",
            )
        )

        assertTrue(settings.isGlobalBackgroundActive())
    }

    @Test
    fun `configured assistant background count includes images and gradients`() {
        val settings = Settings(
            assistants = listOf(
                Assistant(background = null),
                Assistant(background = "   "),
                Assistant(background = "file:///backgrounds/assistant.jpg"),
                Assistant(useGradientBackground = true),
            ),
        )

        assertEquals(2, settings.configuredAssistantBackgroundCount())
    }

    @Test
    fun `global background override replaces assistant image across chat effects`() {
        val assistant = Assistant(
            background = "file:///backgrounds/assistant.jpg",
            backgroundOpacity = 0.4f,
            backgroundBlurRadius = 7f,
            useGradientBackground = true,
        )
        val settings = Settings(
            assistantId = assistant.id,
            assistants = listOf(assistant),
            displaySetting = DisplaySetting(
                showAssistantBubble = true,
                enableBlurEffect = true,
            ),
            advancedAppearanceSetting = AdvancedAppearanceSetting(
                enableGlobalBackground = true,
                globalBackground = "file:///backgrounds/global.jpg",
                globalBackgroundOpacity = 0.92f,
                globalBackgroundBlurRadius = 18f,
                pageSurfaceStyle = BackgroundSurfaceStyle.FROSTED,
                applyGlobalBackgroundToChat = true,
                enableChatDockGlass = true,
            ),
        )

        val resolved = settings.resolveChatBackground()

        assertTrue(settings.isGlobalBackgroundAppliedToChat())
        assertTrue(resolved.usesGlobalBackground)
        assertEquals("file:///backgrounds/global.jpg", resolved.background)
        assertEquals(0.92f, resolved.opacity)
        assertEquals(18f, resolved.blurRadius)
        assertFalse(resolved.useGradientBackground)
        assertTrue(settings.isNavigationGlassActive())
        assertTrue(settings.isChatInputGlassActive())
        assertTrue(settings.isChatDockGlassActive())
        assertTrue(settings.isEnhancedChatBubbleActive())
    }

    @Test
    fun `assistant background returns while global override is unavailable`() {
        val assistant = Assistant(
            background = "file:///backgrounds/assistant.jpg",
            backgroundOpacity = 0.63f,
            backgroundBlurRadius = 9f,
        )
        val settings = Settings(
            assistantId = assistant.id,
            assistants = listOf(assistant),
            advancedAppearanceSetting = AdvancedAppearanceSetting(
                enableGlobalBackground = false,
                globalBackground = "file:///backgrounds/global.jpg",
                applyGlobalBackgroundToChat = true,
            ),
        )

        val resolved = settings.resolveChatBackground()

        assertFalse(settings.isGlobalBackgroundAppliedToChat())
        assertFalse(resolved.usesGlobalBackground)
        assertEquals(assistant.background, resolved.background)
        assertEquals(assistant.backgroundOpacity, resolved.opacity)
        assertEquals(assistant.backgroundBlurRadius, resolved.blurRadius)
    }

    @Test
    fun `transparent global page style keeps overridden chat background unblurred`() {
        val settings = Settings(
            advancedAppearanceSetting = AdvancedAppearanceSetting(
                enableGlobalBackground = true,
                globalBackground = "file:///backgrounds/global.jpg",
                globalBackgroundBlurRadius = 32f,
                pageSurfaceStyle = BackgroundSurfaceStyle.TRANSLUCENT,
                applyGlobalBackgroundToChat = true,
            ),
        )

        assertEquals(0f, settings.resolveChatBackground().blurRadius)
    }

    @Test
    fun `liquid global page style supplies its configured chat blur`() {
        val settings = Settings(
            advancedAppearanceSetting = AdvancedAppearanceSetting(
                enableGlobalBackground = true,
                globalBackground = "file:///backgrounds/global.jpg",
                pageSurfaceStyle = BackgroundSurfaceStyle.LIQUID_GLASS,
                pageLiquidGlassBlurRadius = 11f,
                applyGlobalBackgroundToChat = true,
            ),
        )

        assertEquals(11f, settings.resolveChatBackground().blurRadius)
    }

    @Test
    fun `advanced effects keep conservative defaults`() {
        val appearance = AdvancedAppearanceSetting()

        assertTrue(appearance.enableNavigationGlass)
        assertFalse(appearance.enableChatDockGlass)
        assertEquals(1f, appearance.globalBackgroundOpacity)
        assertFalse(appearance.applyGlobalBackgroundToChat)
        assertEquals(1f, Assistant().backgroundOpacity)
        assertEquals(0.68f, appearance.pageSurfaceOpacity)
        assertEquals(0f, appearance.pageLiquidGlassBlurRadius)
        assertEquals(BackgroundSurfaceStyle.TRANSLUCENT, appearance.pageSurfaceStyle)
        assertEquals(BackgroundSurfaceStyle.OPAQUE, appearance.overlaySurfaceStyle)
        assertEquals(BackgroundSurfaceStyle.LIQUID_GLASS, appearance.navigationSurfaceStyle)
        assertEquals(ChatBubbleStyle.FROSTED, appearance.chatBubbleStyle)
        assertTrue(appearance.enableChatTextReadability)
        assertEquals(1.5f, appearance.chatTextLineHeightRatio)
        assertEquals(0.65f, appearance.chatParagraphSpacingRatio)
        assertEquals(RichContentStyle.TRANSLUCENT, appearance.richContentStyle)
        assertEquals(0.62f, appearance.richContentSurfaceOpacity)
        assertFalse(appearance.enableAutoAccent)
        assertEquals(4f, MIN_GLOBAL_BACKGROUND_BLUR_RADIUS)
        assertEquals(4f, MIN_NAVIGATION_GLASS_BLUR_RADIUS)
        assertEquals(0f, MIN_LIQUID_GLASS_BLUR_RADIUS)
        assertEquals(24f, MAX_LIQUID_GLASS_BLUR_RADIUS)
    }

    @Test
    fun `navigation glass requires an active chat background`() {
        assertFalse(Settings().isNavigationGlassActive())

        val globalOnlySettings = Settings(
            advancedAppearanceSetting = AdvancedAppearanceSetting(
                enableGlobalBackground = true,
                globalBackground = "file:///backgrounds/example.jpg",
            )
        )
        assertFalse(globalOnlySettings.isNavigationGlassActive())

        val assistant = Assistant(background = "file:///backgrounds/assistant.jpg")
        val settings = Settings(
            assistantId = assistant.id,
            assistants = listOf(assistant),
        )
        assertTrue(settings.isNavigationGlassActive())
    }

    @Test
    fun `gradient chat background enables effects but opaque navigation does not`() {
        val assistant = Assistant(useGradientBackground = true)
        val settings = Settings(
            assistantId = assistant.id,
            assistants = listOf(assistant),
            displaySetting = DisplaySetting(
                showAssistantBubble = true,
                enableBlurEffect = true,
            ),
            advancedAppearanceSetting = AdvancedAppearanceSetting(
                enableNavigationGlass = true,
                navigationSurfaceStyle = BackgroundSurfaceStyle.OPAQUE,
                enableChatDockGlass = true,
            ),
        )

        assertTrue(settings.hasActiveChatBackground())
        assertFalse(settings.isNavigationGlassActive())
        assertTrue(settings.isChatInputGlassActive())
        assertTrue(settings.isChatDockGlassActive())
        assertTrue(settings.isEnhancedChatBubbleActive())
    }

    @Test
    fun `blank assistant background does not activate chat effects`() {
        val assistant = Assistant(background = "   ")
        val settings = Settings(
            assistantId = assistant.id,
            assistants = listOf(assistant),
            displaySetting = DisplaySetting(
                showAssistantBubble = true,
                enableBlurEffect = true,
            ),
            advancedAppearanceSetting = AdvancedAppearanceSetting(
                enableNavigationGlass = true,
                enableChatDockGlass = true,
            ),
        )

        assertFalse(settings.hasActiveChatBackground())
        assertFalse(settings.isNavigationGlassActive())
        assertFalse(settings.isChatInputGlassActive())
        assertFalse(settings.isChatDockGlassActive())
        assertFalse(settings.isEnhancedChatBubbleActive())
    }

    @Test
    fun `enhanced bubbles require assistant bubbles and a background`() {
        assertFalse(
            Settings(
                displaySetting = DisplaySetting(showAssistantBubble = true),
                advancedAppearanceSetting = AdvancedAppearanceSetting(
                    enableGlobalBackground = true,
                    globalBackground = "file:///backgrounds/example.jpg",
                ),
            ).isEnhancedChatBubbleActive()
        )
        val assistant = Assistant(background = "file:///backgrounds/assistant.jpg")
        assertTrue(
            Settings(
                assistantId = assistant.id,
                assistants = listOf(assistant),
                displaySetting = DisplaySetting(showAssistantBubble = true),
            ).isEnhancedChatBubbleActive()
        )
    }

    @Test
    fun `chat input glass falls back when no chat background exists`() {
        val displaySetting = DisplaySetting(enableBlurEffect = true)
        assertFalse(Settings(displaySetting = displaySetting).isChatInputGlassActive())

        val assistant = Assistant(background = "file:///backgrounds/assistant.jpg")
        assertTrue(
            Settings(
                assistantId = assistant.id,
                assistants = listOf(assistant),
                displaySetting = displaySetting,
            ).isChatInputGlassActive()
        )
    }

    @Test
    fun `unified chat input adjustments remain active without a background`() {
        val settings = Settings(
            displaySetting = DisplaySetting(
                enableBlurEffect = false,
                inputBlurRadius = 18f,
                inputSurfaceOpacity = 0.42f,
            ),
        )

        assertFalse(settings.hasActiveChatBackground())
        assertEquals(18f, settings.chatInputContainerBlurRadius())
        assertEquals(0.42f, settings.chatInputContainerOpacity())
    }

    @Test
    fun `unified chat input adjustments clamp invalid persisted values`() {
        val settings = Settings(
            displaySetting = DisplaySetting(
                inputBlurRadius = -4f,
                inputSurfaceOpacity = 4f,
            ),
        )

        assertEquals(0f, settings.chatInputContainerBlurRadius())
        assertEquals(1f, settings.chatInputContainerOpacity())
    }

    @Test
    fun `sidebar input and dock glass switches are independent`() {
        val assistant = Assistant(background = "file:///backgrounds/assistant.jpg")
        val base = Settings(
            assistantId = assistant.id,
            assistants = listOf(assistant),
            displaySetting = DisplaySetting(enableBlurEffect = false),
            advancedAppearanceSetting = AdvancedAppearanceSetting(
                enableNavigationGlass = false,
                enableChatDockGlass = true,
            ),
        )

        assertFalse(base.isNavigationGlassActive())
        assertFalse(base.isChatInputGlassActive())
        assertTrue(base.isChatDockGlassActive())

        val inputOnly = base.copy(
            displaySetting = base.displaySetting.copy(enableBlurEffect = true),
            advancedAppearanceSetting = base.advancedAppearanceSetting.copy(
                enableChatDockGlass = false,
            ),
        )
        assertFalse(inputOnly.isNavigationGlassActive())
        assertTrue(inputOnly.isChatInputGlassActive())
        assertFalse(inputOnly.isChatDockGlassActive())
    }

    @Test
    fun `auto accent requires global background toggle and extracted color`() {
        val appearance = AdvancedAppearanceSetting(
            enableGlobalBackground = true,
            globalBackground = "file:///backgrounds/example.jpg",
            enableAutoAccent = true,
        )
        assertFalse(Settings(advancedAppearanceSetting = appearance).isAutoAccentActive())

        assertTrue(
            Settings(
                advancedAppearanceSetting = appearance.copy(autoAccentColorArgb = 0xFF336699),
            ).isAutoAccentActive()
        )
    }

    @Test
    fun `advanced appearance settings survive serialization`() {
        val expected = AdvancedAppearanceSetting(
            enableGlobalBackground = true,
            globalBackground = "file:///backgrounds/example.jpg",
            applyGlobalBackgroundToChat = true,
            pageSurfaceOpacity = 0.64f,
            pageSurfaceStyle = BackgroundSurfaceStyle.FROSTED,
            overlaySurfaceStyle = BackgroundSurfaceStyle.TRANSLUCENT,
            overlaySurfaceOpacity = 0.7f,
            overlaySurfaceBlurRadius = 18f,
            overlayLiquidGlassBlurRadius = 6f,
            enableNavigationGlass = false,
            navigationSurfaceStyle = BackgroundSurfaceStyle.FROSTED,
            navigationGlassOpacity = 0.6f,
            navigationLiquidGlassBlurRadius = 8f,
            enableChatDockGlass = true,
            chatDockGlassOpacity = 0.52f,
            chatDockGlassBlurRadius = 4f,
            chatBubbleStyle = ChatBubbleStyle.LIQUID_GLASS,
            richContentStyle = RichContentStyle.OUTLINED,
            richContentSurfaceOpacity = 0.48f,
            enableAutoAccent = true,
            autoAccentColorArgb = 0xFF336699,
        )

        val encoded = JsonInstant.encodeToString(expected)
        val decoded = JsonInstant.decodeFromString<AdvancedAppearanceSetting>(encoded)

        assertEquals(expected, decoded)
    }

    @Test
    fun `invalid advanced appearance data falls back without breaking settings`() {
        assertEquals(AdvancedAppearanceSetting(), decodeAdvancedAppearanceSetting("not-json"))
    }

    @Test
    fun `older appearance data receives new immersive defaults`() {
        val decoded = decodeAdvancedAppearanceSetting(
            """{"enableGlobalBackground":true}"""
        )

        assertEquals(1f, decoded.globalBackgroundOpacity)
        assertFalse(decoded.applyGlobalBackgroundToChat)
    }

    @Test
    fun `surface styles remain independent`() {
        val appearance = AdvancedAppearanceSetting(
            pageSurfaceStyle = BackgroundSurfaceStyle.FROSTED,
            overlaySurfaceStyle = BackgroundSurfaceStyle.TRANSLUCENT,
            navigationSurfaceStyle = BackgroundSurfaceStyle.LIQUID_GLASS,
            enableNavigationGlass = false,
        )

        assertEquals(BackgroundSurfaceStyle.FROSTED, appearance.pageSurfaceStyle)
        assertEquals(BackgroundSurfaceStyle.TRANSLUCENT, appearance.overlaySurfaceStyle)
        assertEquals(BackgroundSurfaceStyle.LIQUID_GLASS, appearance.navigationSurfaceStyle)
        assertFalse(appearance.enableNavigationGlass)
    }

    @Test
    fun `chat readability values are clamped to supported ranges`() {
        val appearance = AdvancedAppearanceSetting(
            chatTextLineHeightRatio = 9f,
            chatParagraphSpacingRatio = -2f,
        )

        assertEquals(MAX_CHAT_TEXT_LINE_HEIGHT_RATIO, appearance.normalizedChatTextLineHeightRatio())
        assertEquals(
            MIN_CHAT_PARAGRAPH_SPACING_RATIO,
            appearance.normalizedChatParagraphSpacingRatio(),
        )
    }

    @Test
    fun `invalid display data falls back without breaking settings`() {
        assertEquals(DisplaySetting(), decodeDisplaySetting("not-json"))
    }

    @Test
    fun `original chat appearance controls survive serialization`() {
        val expected = DisplaySetting(
            showAssistantBubble = true,
            bubbleOpacity = 0.42f,
            enableBlurEffect = true,
            inputBlurRadius = 7f,
            inputSurfaceOpacity = 0.36f,
            enableTopBarBlur = false,
            topBarBlurRadius = 13f,
            topBarSurfaceOpacity = 0.48f,
        )

        val encoded = JsonInstant.encodeToString(expected)
        val decoded = decodeDisplaySetting(encoded)

        assertEquals(expected, decoded)
    }
}
