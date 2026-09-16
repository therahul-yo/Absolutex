package com.absolutex.feature.settings

import com.absolutex.core.data.settings.NightMode
import com.absolutex.core.data.settings.RotationLock
import com.absolutex.core.gpu.Upscaler
import com.absolutex.model.FitMode
import com.absolutex.model.PageLayout
import com.absolutex.model.PageTransition
import com.absolutex.model.ReadingFlow

fun nightModeLabelRes(mode: NightMode): Int = when (mode) {
    NightMode.OFF -> R.string.settings_night_off
    NightMode.ON -> R.string.settings_night_on
    NightMode.SYSTEM -> R.string.settings_night_system
}

fun readingFlowLabelRes(flow: ReadingFlow): Int = when (flow) {
    ReadingFlow.LTR -> R.string.settings_flow_ltr
    ReadingFlow.RTL -> R.string.settings_flow_rtl
    ReadingFlow.VERTICAL -> R.string.settings_flow_vertical
}

fun fitModeLabelRes(mode: FitMode): Int = when (mode) {
    FitMode.FIT_SCREEN -> R.string.settings_fit_screen
    FitMode.FULL_SIZE -> R.string.settings_fit_full
    FitMode.FIT_WIDTH -> R.string.settings_fit_width
    FitMode.FIT_HEIGHT -> R.string.settings_fit_height
}

fun pageLayoutLabelRes(layout: PageLayout): Int = when (layout) {
    PageLayout.SINGLE -> R.string.settings_layout_single
    PageLayout.DOUBLE -> R.string.settings_layout_double
    PageLayout.DOUBLE_WITH_COVER -> R.string.settings_layout_cover
    PageLayout.CONTINUOUS_VERTICAL -> R.string.settings_layout_continuous
}

fun transitionLabelRes(transition: PageTransition): Int = when (transition) {
    PageTransition.SLIDE -> R.string.settings_transition_slide
    PageTransition.PAGE_OVER -> R.string.settings_transition_page_over
    PageTransition.REVEAL -> R.string.settings_transition_reveal
}

fun rotationLockLabelRes(lock: RotationLock): Int = when (lock) {
    RotationLock.SYSTEM -> R.string.settings_rotation_system
    RotationLock.PORTRAIT -> R.string.settings_rotation_portrait
    RotationLock.LANDSCAPE -> R.string.settings_rotation_landscape
}

fun upscalerLabelRes(upscaler: Upscaler): Int = when (upscaler) {
    Upscaler.PLATFORM -> R.string.settings_upscaler_platform
    Upscaler.MITCHELL -> R.string.settings_upscaler_mitchell
    Upscaler.LANCZOS -> R.string.settings_upscaler_lanczos
}
