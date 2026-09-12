package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.resolveChatBackground
import me.rerere.rikkahub.ui.components.ui.BlurredBackgroundImage
import me.rerere.rikkahub.ui.components.ui.GradientBackgroundSpec
import me.rerere.rikkahub.ui.components.ui.LocalGlassBackdrop
import me.rerere.rikkahub.ui.components.ui.glassBackdropSource

@Composable
fun AssistantBackground(
    setting: Settings,
    modifier: Modifier,
    interactionInProgress: Boolean = false,
) {
    val background = setting.resolveChatBackground()
    AnimatedContent(
        targetState = background,
        modifier = modifier.fillMaxSize().glassBackdropSource(LocalGlassBackdrop.current),
        transitionSpec = {
            if (initialState.transitionIdentity() == targetState.transitionIdentity()) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(animationSpec = tween(durationMillis = 320)) togetherWith
                    fadeOut(animationSpec = tween(durationMillis = 240))
            }
        },
        label = "AssistantBackgroundTransition",
    ) { target ->
        if (target.useGradientBackground) {
            MeshGradientBackground(
                spec = GradientBackgroundSpec(
                    opacity = target.opacity,
                    animationEnabled = target.gradientAnimation,
                    speed = target.gradientSpeed,
                    followTheme = target.gradientFollowTheme,
                    preset = target.gradientPreset,
                    customColors = target.gradientCustomColors,
                    intensity = target.gradientIntensity,
                    motionScale = target.gradientMotionScale,
                    blobCount = target.gradientBlobCount,
                    softness = target.gradientSoftness,
                    angle = target.gradientAngle,
                    vignette = target.gradientVignette,
                    performanceEffectsEnabled = setting.advancedAppearanceSetting
                        .enableGradientPerformanceEffects,
                    rendererMode = setting.advancedAppearanceSetting.gradientRendererMode,
                    interactionInProgress = interactionInProgress,
                    respectSystemReducedMotion = setting.advancedAppearanceSetting
                        .respectSystemReducedMotion,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!target.background.isNullOrBlank()) {
            BlurredBackgroundImage(
                background = target.background,
                opacity = target.opacity,
                blurRadius = target.blurRadius,
                overlayTopAlpha = 0.32f,
                overlayBottomAlpha = 0.52f,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun me.rerere.rikkahub.data.datastore.ResolvedChatBackground.transitionIdentity(): String =
    buildString {
        append(usesGlobalBackground)
        append(':')
        append(useGradientBackground)
        append(':')
        append(background.orEmpty())
    }
