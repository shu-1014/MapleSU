package com.maplesu.core.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.dropUnlessResumed
import com.maplesu.core.ui.LocalUiMode
import com.maplesu.core.ui.UiMode
import com.maplesu.core.ui.navigation3.LocalNavigator
import com.maplesu.core.ui.navigation3.Navigator
import com.maplesu.core.ui.navigation3.Route
import com.maplesu.core.ui.viewmodel.SettingsViewModel
import dev.maplesu.manager.workmode.ui.WorkModeSwitchSheet
import com.maplesu.core.R
import com.maplesu.core.ui.screen.iqoo.IqooRootActivity

@Composable
fun SettingPager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
    /** true = 只显示「界面与外观」卡片（独立子页面）。 */
    appearanceOnly: Boolean = false,
    onBack: (() -> Unit)? = null,
) {
    val viewModel = viewModel<SettingsViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val latestIsCurrentPage by rememberUpdatedState(isCurrentPage)
    val initialResumeHandled = rememberSaveable { mutableStateOf(false) }
    var showWorkModeSheet by rememberSaveable { mutableStateOf(false) }
    var showUltraPolicyRisk by rememberSaveable { mutableStateOf(false) }
    // Keep each native layout's position outside the skin branch; their pixel offsets differ.
    val miuixListState = rememberLazyListState()
    val materialScrollState = rememberScrollState()
    val miuixTopBarState = top.yukonga.miuix.kmp.basic.rememberTopAppBarState()
    val materialTopBarState = androidx.compose.material3.rememberTopAppBarState()

    val appearanceRevision = com.maplesu.core.ui.util.WallpaperPrefs.observe()
    val detectionRevision by dev.maplesu.manager.workmode.ModeRuntime.detectionRevision.collectAsStateWithLifecycle()
    LaunchedEffect(isCurrentPage, appearanceRevision, detectionRevision) {
        if (isCurrentPage) viewModel.refresh()
    }

    LifecycleResumeEffect(Unit) {
        if (initialResumeHandled.value && latestIsCurrentPage) {
            viewModel.refresh()
        }
        initialResumeHandled.value = true
        onPauseOrDispose { }
    }

    val actions = SettingsScreenActions(
        onSetCheckUpdate = viewModel::setCheckUpdate,
        onSetCheckModuleUpdate = viewModel::setCheckModuleUpdate,
        onOpenTheme = { navigator.push(Route.ColorPalette) },
        onOpenAppearance = { navigator.push(Route.Appearance) },
        onOpenImages = { navigator.push(Route.AppearanceImages) },
        onOpenWallpaper = { navigator.push(Route.Wallpaper) },
        onOpenThemeStore = { navigator.push(Route.ThemeStore) },
        onOpenFont = { navigator.push(Route.ThemeFont) },
        onOpenLayout = { navigator.push(Route.ThemeLayout) },
        onSetUiModeIndex = { index ->
            viewModel.setUiMode(if (index == 0) UiMode.Miuix.value else UiMode.Material.value)
        },
        onOpenProfileTemplate = { navigator.push(Route.AppProfileTemplate) },
        onSetSuCompatMode = viewModel::setSuCompatMode,
        onSetKernelUmountEnabled = viewModel::setKernelUmountEnabled,
        onSetSelinuxHideEnabled = viewModel::setSelinuxHideEnabled,
        onSetJailbreakSelinuxHide = viewModel::setJailbreakSelinuxHide,
        onSetUltraModulePolicyLoad = viewModel::setUltraModulePolicyLoad,
        onSetUltraModulePolicyHide = { enabled ->
            if (enabled && uiState.ultraModulePolicy.hideNeedsRiskConfirmation) showUltraPolicyRisk = true
            else viewModel.setUltraModulePolicyHide(enabled)
        },
        onSetSulogEnabled = viewModel::setSulogEnabled,
        onSetAdbRootEnabled = viewModel::setAdbRootEnabled,
        onSetShellSuAdbOnlyEnabled = viewModel::setShellSuAdbOnlyEnabled,
        onSetDefaultUmountModules = viewModel::setDefaultUmountModules,
        onSetEnableWebDebugging = viewModel::setEnableWebDebugging,
        onSetAutoJailbreak = viewModel::setAutoJailbreak,
        onSetUseSoftReboot = viewModel::setUseSoftReboot,
        onOpenAbout = { navigator.push(Route.About) },
        onOpenWorkMode = {
            if (!uiState.hiddenMasked && uiState.workModeEntry.showSettingsRow) showWorkModeSheet = true
        },
        onSetStealthEnabled = viewModel::setStealthEnabled,
        onOpenNormalHidden = viewModel::openNormalHidden,
        onSetWebAdminEnabled = viewModel::setWebAdminEnabled,
        onCopyWebAdminUrl = viewModel::copyWebAdminUrl,
        onOpenWebAdminUrl = viewModel::openWebAdminUrl,
        onOpenLiteHidden = dropUnlessResumed { navigator.push(Route.LiteHidden) },
        onOpenWebUiSelfCheck = dropUnlessResumed { navigator.push(Route.WebUiSelfCheck) },
        onOpenIqooRoot = {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            ctx.startActivity(android.content.Intent(ctx, IqooRootActivity::class.java))
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> SettingPagerMiuix(uiState, actions, bottomInnerPadding, appearanceOnly, onBack, miuixListState, miuixTopBarState)
        UiMode.Material -> SettingPagerMaterial(uiState, actions, bottomInnerPadding, appearanceOnly, onBack, materialScrollState, materialTopBarState)
    }

    if (!appearanceOnly) {
        if (showUltraPolicyRisk && !uiState.hiddenMasked &&
            uiState.modePolicy.shows(dev.maplesu.manager.workmode.ui.SettingsSection.ULTRA_MODULE_POLICY) &&
            uiState.ultraModulePolicy.hideNeedsRiskConfirmation) {
            ThemeDialog(
                title = stringResource(R.string.ultra_module_policy_risk_title),
                onDismiss = { showUltraPolicyRisk = false },
                confirm = stringResource(R.string.ultra_module_policy_risk_confirm),
                onConfirm = {
                    showUltraPolicyRisk = false
                    viewModel.setUltraModulePolicyHide(true, acceptUnverified = true)
                },
            ) { ThemeText(stringResource(R.string.ultra_module_policy_risk_message)) }
        }
        WorkModeSwitchSheet(show = showWorkModeSheet && !uiState.hiddenMasked, onDismiss = { showWorkModeSheet = false })
        NormalHiddenDialog(
            ui = uiState.normalHidden,
            onDismiss = viewModel::dismissNormalHidden,
            onPrepare = viewModel::prepareNormalHidden,
            onCopyLink = viewModel::copyNormalHiddenLink,
            onOpenLink = viewModel::openNormalHiddenLink,
            onSaveLink = viewModel::saveNormalHiddenLink,
            onConfirm = viewModel::confirmNormalHidden,
            onPortChange = viewModel::setNormalHiddenPort,
        )
    }
}

/**
 * 「界面与外观」子页面：界面风格 / 主题 / 状态卡图片 / 导航图标 / 枫叶飘落 / 壁纸
 * 全部收在这一页，设置首页只保留一个入口。
 */
@Composable
fun AppearanceScreen() {
    val navigator = LocalNavigator.current
    SettingPager(
        navigator = navigator,
        bottomInnerPadding = 0.dp,
        isCurrentPage = true,
        appearanceOnly = true,
        onBack = { navigator.pop() },
    )
}
