package com.maplesu.core.ui.screen.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maplesu.core.R
import com.maplesu.core.ui.component.dialog.rememberLoadingDialog
import com.maplesu.core.ui.component.miuix.SendLogDialog
import com.maplesu.core.ui.component.uninstalldialog.UninstallDialog
import com.maplesu.core.ui.theme.LocalEnableBlur
import com.maplesu.core.ui.util.BlurredBar
import com.maplesu.core.ui.util.miuixBarColor
import com.maplesu.core.ui.util.rememberBlurBackdrop
import dev.maplesu.manager.workmode.ui.KsuRowId
import dev.maplesu.manager.workmode.ui.SettingsSection
import dev.maplesu.manager.workmode.ui.SettingsStatusCopy
import dev.maplesu.manager.workmode.ui.settingsStatusOf
import dev.maplesu.manager.workmode.ui.visibleKsuRows
import dev.maplesu.manager.workmode.ui.workModeNameRes
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 设置主页（Miuix）。
 *
 * 分组（§8）：外观 / 工作模式与隐藏 / 工作环境 / 通用 / 模式专属 / 卸载与关于。
 * 卡片间距统一 12dp，同一张卡片里每行都有图标，文字左缘对齐。
 * 整页只消费 [SettingsUiState]：组合期不读 prefs / JNI，首帧结构由缓存快照决定。
 *
 * @author weishu
 * @date 2023/1/1.
 */
@Composable
fun SettingPagerMiuix(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    bottomInnerPadding: Dp,
    // true = 只渲染「界面与外观」这一张卡（独立子页面）；false = 常规设置页。
    appearanceOnly: Boolean = false,
    onBack: (() -> Unit)? = null,
    listState: LazyListState,
    topBarState: TopAppBarState,
) {
    val scrollBehavior = MiuixScrollBehavior(topBarState)
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = miuixBarColor(blurActive)
    val loadingDialog = rememberLoadingDialog()
    var showSendLogDialog by rememberSaveable { mutableStateOf(false) }
    var showUninstall by rememberSaveable { mutableStateOf(false) }
    // 2026-10-31 模式独立性：设置页只按 uiState.modePolicy 渲染；
    // 工作环境面板只在 Ultra 专属 section 可见时才构造（Normal/未判定零 Ultra SDK 探测），
    // 隐藏/隐身生效时不构造：这张卡带模式字样（不变量 §2.1）。
    val modePolicy = uiState.modePolicy
    val ultraPanel = rememberUltraEnvironmentPanel(
        enabled = !appearanceOnly && modePolicy.shows(SettingsSection.ULTRA_ULTRA_ENV) && !uiState.hiddenMasked,
    )
    val workModeEntry = uiState.workModeEntry
    val bf = uiState.bFeatures

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(if (appearanceOnly) R.string.ui_d_appearance_title else R.string.settings),
                    navigationIcon = {
                        onBack?.let { back ->
                            IconButton(onClick = back) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.ui_d_back),
                                    tint = colorScheme.onSurface,
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                if (appearanceOnly) {
                    item(key = "appearance") {
                        AppearanceBodyMiuix(uiState, actions)
                        Spacer(
                            Modifier.height(
                                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp,
                            ),
                        )
                    }
                    return@LazyColumn
                }

                item(key = "appearance-entry") {
                    SettingsCardMiuix(Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(160), fadeOutSpec = null)) {
                        ArrowRowMiuix(
                            title = stringResource(R.string.ui_d_appearance_title),
                            summary = stringResource(R.string.ui_d_appearance_summary),
                            icon = Icons.Rounded.Palette,
                            onClick = actions.onOpenAppearance,
                        )
                    }
                }

                // 工作模式与隐藏：同组一张卡片。入口是否可见只看 policy / WorkModeEntryPolicy。
                val showWorkMode = workModeEntry.showSettingsRow && modePolicy.shows(SettingsSection.WORK_MODE)
                val showHidden = modePolicy.shows(SettingsSection.ULTRA_HIDDEN) && uiState.liteHiddenCard.showsRow
                if (showWorkMode || showHidden) {
                    item(key = "work-mode") {
                        SettingsCardMiuix(Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(160), fadeOutSpec = null)) {
                            if (showWorkMode) {
                                ArrowRowMiuix(
                                    title = stringResource(R.string.work_mode),
                                    summary = modePolicy.mode?.let { mode ->
                                        stringResource(R.string.work_mode_entry_summary, stringResource(workModeNameRes(mode)))
                                    } ?: stringResource(R.string.work_mode_settings_row_summary),
                                    icon = Icons.Rounded.SwapHoriz,
                                    onClick = actions.onOpenWorkMode,
                                )
                            }
                            // H3/H7：Lite 隐藏「设置项」（独立页；仅 Ultra 且未隐藏时可见）。
                            // Normal 的隐藏语义是 KSU stealth，不使用 Lite 寄生入口。
                            if (showHidden) {
                                dev.maplesu.manager.workmode.ui.LiteHiddenEntry(
                                    ui = uiState.liteHiddenCard,
                                    onClick = actions.onOpenLiteHidden,
                                )
                            }
                        }
                    }
                }

                // Ultra 专属：MapleSU_Ultra工作环境（panel 为 null 时说明未获许可，连组合都不发生）。
                ultraPanel?.let { panel ->
                    item(key = "ultra-env") {
                        Box(Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(160), fadeOutSpec = null)) {
                            UltraEnvironmentSectionMiuix(panel)
                        }
                    }
                }

                if (uiState.jailbreakSelinuxHide.visible && !uiState.hiddenMasked) {
                    item(key = "jailbreak-selinux-hide") {
                        SettingsCardMiuix(Modifier.animateItem()) {
                            SwitchRowMiuix(
                                title = stringResource(R.string.jailbreak_selinux_hide),
                                summary = stringResource(R.string.jailbreak_selinux_hide_summary) + "\n" +
                                    stringResource(uiState.jailbreakSelinuxHide.statusRes()),
                                icon = Icons.Rounded.VisibilityOff,
                                checked = uiState.jailbreakSelinuxHide.requested,
                                onCheckedChange = actions.onSetJailbreakSelinuxHide,
                            )
                        }
                    }
                }

                if (modePolicy.shows(SettingsSection.ULTRA_MODULE_POLICY) && !uiState.hiddenMasked) {
                    item(key = "ultra-module-policy") {
                        val policy = uiState.ultraModulePolicy
                        SettingsCardMiuix(Modifier.animateItem()) {
                            SwitchRowMiuix(
                                title = stringResource(R.string.ultra_module_policy_load),
                                summary = stringResource(R.string.ultra_module_policy_load_summary) +
                                    policy.statusRes()?.let { "\n" + stringResource(it) }.orEmpty(),
                                icon = Icons.Rounded.Shield, checked = policy.loadRequested,
                                enabled = policy.operable, onCheckedChange = actions.onSetUltraModulePolicyLoad,
                            )
                            AnimatedVisibility(
                                visible = policy.loadRequested,
                                enter = expandVertically(tween(180)) + fadeIn(tween(120)),
                                exit = shrinkVertically(tween(180)) + fadeOut(tween(100)),
                            ) {
                                SwitchRowMiuix(
                                    title = stringResource(R.string.ultra_module_policy_hide),
                                    summary = stringResource(R.string.ultra_module_policy_hide_summary) +
                                        if (policy.hideNeedsRiskConfirmation) "\n" +
                                            stringResource(R.string.ultra_module_policy_hide_try)
                                        else if (policy.hideOperable) "" else "\n" +
                                            stringResource(policy.hideUnavailableRes()),
                                    icon = Icons.Rounded.VisibilityOff, checked = policy.hideRequested,
                                    enabled = policy.hideOperable, onCheckedChange = actions.onSetUltraModulePolicyHide,
                                )
                            }
                        }
                    }
                }

                // Normal（KSU）专属：传统 SU 命令支持 + 六项内核设置（行级再受能力位约束）。
                val ksuRows = if (modePolicy.shows(SettingsSection.KSU_FEATURES)) {
                    visibleKsuRows(uiState.capabilities)
                        .filterNot { it == KsuRowId.SELINUX_HIDE && uiState.jailbreakSelinuxHide.visible }
                } else {
                    emptyList()
                }
                val showSuCompat = modePolicy.shows(SettingsSection.SU_COMPAT) && uiState.managerValid
                if (showSuCompat || ksuRows.isNotEmpty()) {
                    item(key = "ksu-features") {
                        SettingsCardMiuix(Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(160), fadeOutSpec = null)) {
                            if (showSuCompat) SuCompatRowMiuix(uiState, actions)
                            ksuRows.forEach { row -> KsuSwitchRowMiuix(row, uiState, actions) }
                        }
                    }
                }

                // 通用：检查模块更新 + 软重启 + WebView 调试（两模式都消费）。
                val showModuleUpdate = modePolicy.shows(SettingsSection.MODULE_UPDATE) && uiState.managerValid
                val showTuning = modePolicy.shows(SettingsSection.GENERAL_TUNING) && uiState.managerValid
                if (showModuleUpdate || showTuning) {
                    item(key = "general") {
                            SettingsCardMiuix(Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(160), fadeOutSpec = null)) {
                                if (showModuleUpdate) {
                                    SwitchRowMiuix(
                                        title = stringResource(R.string.settings_module_check_update),
                                        summary = stringResource(R.string.settings_check_update_summary),
                                        icon = Icons.Rounded.SystemUpdateAlt,
                                        checked = uiState.checkModuleUpdate,
                                        onCheckedChange = actions.onSetCheckModuleUpdate,
                                    )
                                }
                                if (showTuning) {
                                    SwitchRowMiuix(
                                        title = stringResource(R.string.settings_soft_reboot),
                                        summary = stringResource(R.string.settings_soft_reboot_summary),
                                        icon = Icons.Rounded.RestartAlt,
                                        checked = uiState.useSoftReboot,
                                        onCheckedChange = actions.onSetUseSoftReboot,
                                    )
                                    SwitchRowMiuix(
                                        title = stringResource(R.string.enable_web_debugging),
                                        summary = stringResource(R.string.enable_web_debugging_summary),
                                        icon = Icons.Rounded.DeveloperMode,
                                        checked = uiState.enableWebDebugging,
                                        onCheckedChange = actions.onSetEnableWebDebugging,
                                    )
                                }
                            }
                    }
                }

                // Normal（KSU）专属：App Profile 模板入口 + 内核形态只读行。
                val showProfile = modePolicy.shows(SettingsSection.KSU_APP_PROFILE)
                val showKernelMode = modePolicy.shows(SettingsSection.KSU_KERNEL_MODE)
                if (showProfile || showKernelMode) {
                    item(key = "ksu-profile") {
                        SettingsCardMiuix(Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(160), fadeOutSpec = null)) {
                            if (showProfile) {
                                ArrowRowMiuix(
                                    title = stringResource(R.string.settings_profile_template),
                                    summary = stringResource(R.string.settings_profile_template_summary),
                                    icon = Icons.Rounded.Person,
                                    onClick = actions.onOpenProfileTemplate,
                                )
                            }
                            if (showKernelMode) {
                                val title = stringResource(R.string.settings_kernel_mode)
                                BasicComponent(
                                    title = title,
                                    summary = stringResource(
                                        if (uiState.isLkmMode) R.string.settings_kernel_mode_lkm else R.string.settings_kernel_mode_builtin,
                                    ),
                                    startAction = { RowIconMiuix(Icons.Rounded.Memory, title) },
                                )
                            }
                        }
                    }
                }

                // W18 三卡（Normal 专属；能力门控 + 诚实降级，见 workmode/ui/SettingsBFeatures.kt）。
                // 模式由 SettingsModePolicy 裁决；Ultra 下 bFeatures 恒 NONE，且 VM 不读 KSU 状态。
                val showStealth = modePolicy.shows(SettingsSection.KSU_STEALTH) && bf.stealth.show
                val showAppLock = modePolicy.shows(SettingsSection.KSU_APP_LOCK) && bf.appLock.show
                val showWebAdmin = modePolicy.shows(SettingsSection.KSU_WEB_ADMIN) && bf.webAdmin.show
                if (showStealth || showAppLock || showWebAdmin) {
                    item(key = "ksu-b-features") {
                        SettingsCardMiuix(Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(160), fadeOutSpec = null)) {
                            if (showStealth) StealthRowMiuix(uiState, actions)
                            if (showAppLock) {
                                val title = stringResource(R.string.work_mode_applock_title)
                                BasicComponent(
                                    title = title,
                                    summary = stringResource(R.string.work_mode_applock_unavailable),
                                    startAction = { RowIconMiuix(Icons.Rounded.Lock, title) },
                                )
                            }
                            if (showWebAdmin) WebAdminRowsMiuix(uiState, actions)
                        }
                    }
                }

                // 卸载 + 日志 + 关于：与 KSU 一样放在最后。
                val showNormalUninstall = modePolicy.shows(SettingsSection.KSU_UNINSTALL) &&
                    uiState.managerValid && !uiState.hiddenMasked
                item(key = "last") {
                    if (ultraPanel != null || showNormalUninstall || modePolicy.shows(SettingsSection.LOG_AND_ABOUT)) {
                        SettingsCardMiuix(Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(160), fadeOutSpec = null)) {
                            ultraPanel?.let { panel -> UltraUninstallRowMiuix(panel) }
                            if (showNormalUninstall) {
                                ArrowRowMiuix(
                                    title = stringResource(R.string.uninstall),
                                    summary = null,
                                    icon = Icons.Rounded.Delete,
                                    onClick = { showUninstall = true },
                                )
                                UninstallDialog(show = showUninstall, onDismissRequest = { showUninstall = false })
                            }
                            if (modePolicy.shows(SettingsSection.LOG_AND_ABOUT)) {
                                ArrowRowMiuix(
                                    title = stringResource(R.string.send_log),
                                    summary = null,
                                    icon = Icons.Rounded.BugReport,
                                    onClick = { showSendLogDialog = true },
                                )
                                SendLogDialog(
                                    show = showSendLogDialog,
                                    onDismissRequest = { showSendLogDialog = false },
                                    loadingDialog = loadingDialog,
                                )
                                ArrowRowMiuix(
                                    title = stringResource(R.string.about),
                                    summary = null,
                                    icon = Icons.Rounded.Info,
                                    onClick = actions.onOpenAbout,
                                )
                                ArrowRowMiuix(
                                    title = stringResource(R.string.iqoo_root_entry),
                                    summary = stringResource(R.string.iqoo_root_entry_summary),
                                    icon = Icons.Rounded.Info,
                                    onClick = actions.onOpenIqooRoot,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(bottomInnerPadding + 12.dp))
                }
            }
        }
    }
}

/** 卡片之间统一 12dp。 */
@Composable
private fun SettingsCardMiuix(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(160)),
        content = content,
    )
}

@Composable
private fun RowIconMiuix(icon: ImageVector, description: String?) {
    Icon(
        icon,
        modifier = Modifier.padding(end = 6.dp),
        contentDescription = description,
        tint = colorScheme.onBackground,
    )
}

@Composable
private fun ArrowRowMiuix(title: String, summary: String?, icon: ImageVector, onClick: () -> Unit) {
    ArrowPreference(
        title = title,
        summary = summary,
        startAction = { RowIconMiuix(icon, title) },
        onClick = onClick,
    )
}

@Composable
private fun SwitchRowMiuix(
    title: String,
    summary: String?,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SwitchPreference(
        title = title,
        summary = summary,
        startAction = { RowIconMiuix(icon, title) },
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
private fun SuCompatRowMiuix(uiState: SettingsUiState, actions: SettingsScreenActions) {
    val items = listOf(
        stringResource(R.string.settings_mode_enable_by_default),
        stringResource(R.string.settings_mode_disable_always),
    )
    val summary = when {
        uiState.suCompatStatus == "unsupported" -> stringResource(R.string.feature_status_unsupported_summary)
        uiState.suCompatStatus == "unknown" -> stringResource(R.string.feature_status_unreadable_summary)
        uiState.suCompatStatus == "managed" -> stringResource(R.string.feature_status_managed_summary)
        // This switch looks like a revocation control, so while it is off its own summary has
        // to state that delivered copies stay in place until they are revoked per application.
        !uiState.isSuEnabled -> stringResource(R.string.maplesu_su_off_keeps_delivered)
        else -> stringResource(R.string.settings_sucompat_summary)
    }
    val title = stringResource(R.string.settings_sucompat)
    OverlayDropdownPreference(
        title = title,
        summary = summary,
        items = items,
        startAction = { RowIconMiuix(Icons.Rounded.AdminPanelSettings, title) },
        enabled = uiState.suCompatStatus == "supported",
        selectedIndex = if (uiState.isSuEnabled) 0 else 1,
        onSelectedIndexChange = { actions.onSetSuCompatMode(if (it == 0) 0 else 2) },
    )
}

@Composable
private fun KsuSwitchRowMiuix(row: KsuRowId, uiState: SettingsUiState, actions: SettingsScreenActions) {
    when (row) {
        KsuRowId.KERNEL_UMOUNT -> SwitchRowMiuix(
            title = stringResource(R.string.settings_kernel_umount),
            summary = stringResource(R.string.settings_kernel_umount_summary),
            icon = Icons.Rounded.FolderOff,
            checked = uiState.isKernelUmountEnabled,
            onCheckedChange = actions.onSetKernelUmountEnabled,
        )

        KsuRowId.SELINUX_HIDE -> SwitchRowMiuix(
            title = stringResource(R.string.settings_selinux_hide),
            summary = stringResource(R.string.settings_selinux_hide_summary),
            icon = Icons.Rounded.Shield,
            checked = uiState.isSelinuxHideEnabled,
            onCheckedChange = actions.onSetSelinuxHideEnabled,
        )

        KsuRowId.SULOG -> SwitchRowMiuix(
            title = stringResource(R.string.settings_sulog),
            summary = stringResource(R.string.settings_sulog_summary),
            icon = Icons.Rounded.History,
            checked = uiState.isSulogEnabled,
            onCheckedChange = actions.onSetSulogEnabled,
        )

        KsuRowId.ADB_ROOT -> SwitchRowMiuix(
            title = stringResource(R.string.settings_adb_root),
            summary = stringResource(R.string.settings_adb_root_summary),
            icon = Icons.Rounded.Adb,
            checked = uiState.isAdbRootEnabled,
            onCheckedChange = actions.onSetAdbRootEnabled,
        )

        KsuRowId.SHELL_SU_ADB_ONLY -> SwitchRowMiuix(
            title = stringResource(R.string.settings_shell_su_adb_only),
            summary = stringResource(R.string.settings_shell_su_adb_only_summary),
            icon = Icons.Rounded.Terminal,
            checked = uiState.isShellSuAdbOnlyEnabled,
            onCheckedChange = actions.onSetShellSuAdbOnlyEnabled,
        )

        KsuRowId.UMOUNT_MODULES_DEFAULT -> SwitchRowMiuix(
            title = stringResource(R.string.settings_umount_modules_default),
            summary = stringResource(R.string.settings_umount_modules_default_summary),
            icon = Icons.Rounded.Extension,
            checked = uiState.isDefaultUmountModules,
            onCheckedChange = actions.onSetDefaultUmountModules,
        )
    }
}

@Composable
private fun StealthRowMiuix(uiState: SettingsUiState, actions: SettingsScreenActions) {
    val card = uiState.bFeatures.stealth
    // 状态行只陈述事实：只有明确 ON 才说「已开启」；UNKNOWN 必须说「状态未知（读取失败）」。
    val status = stringResource(SettingsStatusCopy.statusRes(settingsStatusOf(card)))
    val title = stringResource(R.string.work_mode_stealth_title)
    if (card.operable && !card.enabled) {
        ArrowRowMiuix(
            title = title,
            summary = stringResource(R.string.normal_hidden_entry_summary),
            icon = Icons.Rounded.VisibilityOff,
            onClick = actions.onOpenNormalHidden,
        )
    } else if (card.operable) {
        SwitchRowMiuix(
            title = title,
            summary = status + " · " + stringResource(R.string.work_mode_stealth_summary),
            icon = Icons.Rounded.VisibilityOff,
            checked = card.enabled,
            onCheckedChange = actions.onSetStealthEnabled,
        )
    } else {
        BasicComponent(
            title = title,
            summary = status + " · " + stringResource(R.string.work_mode_stealth_unavailable),
            startAction = { RowIconMiuix(Icons.Rounded.VisibilityOff, title) },
        )
    }
}

@Composable
private fun WebAdminRowsMiuix(uiState: SettingsUiState, actions: SettingsScreenActions) {
    val card = uiState.bFeatures.webAdmin
    val title = stringResource(R.string.work_mode_webadmin_title)
    if (card.operable) {
        SwitchRowMiuix(
            title = title,
            summary = stringResource(R.string.work_mode_webadmin_summary),
            icon = Icons.Rounded.Language,
            checked = card.enabled,
            onCheckedChange = actions.onSetWebAdminEnabled,
        )
        ArrowRowMiuix(
            title = stringResource(R.string.work_mode_webadmin_copy),
            summary = null,
            icon = Icons.Rounded.Language,
            onClick = actions.onCopyWebAdminUrl,
        )
        ArrowRowMiuix(
            title = stringResource(R.string.work_mode_webadmin_open),
            summary = null,
            icon = Icons.Rounded.Language,
            onClick = actions.onOpenWebAdminUrl,
        )
    } else {
        BasicComponent(
            title = title,
            summary = stringResource(R.string.work_mode_webadmin_unavailable),
            startAction = { RowIconMiuix(Icons.Rounded.Language, title) },
        )
    }
}

@Composable
private fun AppearanceBodyMiuix(uiState: SettingsUiState, actions: SettingsScreenActions) {
    AppearanceSettingsBody(uiState, actions)
}
