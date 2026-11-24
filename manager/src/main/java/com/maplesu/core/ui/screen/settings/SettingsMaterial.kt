package com.maplesu.core.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Adb
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maplesu.core.R
import com.maplesu.core.ui.component.material.ExpressiveScaffold
import com.maplesu.core.ui.component.material.SegmentedColumn
import com.maplesu.core.ui.component.material.SegmentedDropdownItem
import com.maplesu.core.ui.component.material.SegmentedListItem
import com.maplesu.core.ui.component.material.SegmentedSwitchItem
import com.maplesu.core.ui.component.material.SendLogBottomSheet
import com.maplesu.core.ui.component.material.SnackBarHost
import com.maplesu.core.ui.component.material.TopBarBackButton
import com.maplesu.core.ui.component.material.expressiveTopAppBarColors
import com.maplesu.core.ui.component.uninstalldialog.UninstallDialog
import dev.maplesu.manager.workmode.ui.KsuRowId
import dev.maplesu.manager.workmode.ui.SettingsSection
import dev.maplesu.manager.workmode.ui.SettingsStatusCopy
import dev.maplesu.manager.workmode.ui.settingsStatusOf
import dev.maplesu.manager.workmode.ui.visibleKsuRows
import dev.maplesu.manager.workmode.ui.workModeNameRes

/** 分组之间的间距沿用设置主页原有写法。 */
private val GroupPadding = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
    .animateContentSize(animationSpec = tween(160))

/**
 * 设置主页（Material）。分组与 Miuix 一一对应，容器用 `SegmentedColumn`。
 * 整页只消费 [SettingsUiState]：组合期不读 prefs / JNI，首帧结构由缓存快照决定。
 *
 * @author weishu
 * @date 2023/1/1.
 */
@Composable
fun SettingPagerMaterial(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    bottomInnerPadding: Dp,
    // true = 只渲染「界面与外观」这一组（独立子页面）；false = 常规设置页。
    appearanceOnly: Boolean = false,
    onBack: (() -> Unit)? = null,
    scrollState: ScrollState,
    topBarState: TopAppBarState,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topBarState)
    val snackBarHost = remember { SnackbarHostState() }
    // 2026-10-31 模式独立性：设置页只按 uiState.modePolicy 渲染；
    // 工作环境面板只在 Ultra 专属 section 可见时才构造（Normal/未判定零 Ultra SDK 探测），
    // 隐藏/隐身生效时不构造：这张卡带模式字样（不变量 §2.1）。
    val modePolicy = uiState.modePolicy
    val ultraPanel = rememberUltraEnvironmentPanel(
        enabled = !appearanceOnly && modePolicy.shows(SettingsSection.ULTRA_ULTRA_ENV) && !uiState.hiddenMasked,
    )
    var showBottomSheet by rememberSaveable { mutableStateOf(false) }
    var showUninstall by rememberSaveable { mutableStateOf(false) }

    ExpressiveScaffold(
        topBar = {
            TopBar(
                title = stringResource(if (appearanceOnly) R.string.ui_d_appearance_title else R.string.settings),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackBarHost(hostState = snackBarHost, modifier = Modifier.padding(bottom = bottomInnerPadding)) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(scrollState),
        ) {
            if (appearanceOnly) {
                AppearanceBodyMaterial(uiState, actions)
            } else {
                SegmentedColumn(
                    modifier = GroupPadding,
                    content = listOf {
                        ArrowRowMaterial(
                            title = stringResource(R.string.ui_d_appearance_title),
                            summary = stringResource(R.string.ui_d_appearance_summary),
                            icon = Icons.Filled.Palette,
                            onClick = actions.onOpenAppearance,
                        )
                    },
                )

                // 工作模式与隐藏：同组。入口是否可见只看 policy / WorkModeEntryPolicy。
                val activeMode = modePolicy.mode
                SegmentedColumn(
                    modifier = GroupPadding,
                    content = buildList {
                        if (uiState.workModeEntry.showSettingsRow && modePolicy.shows(SettingsSection.WORK_MODE)) {
                            add {
                                ArrowRowMaterial(
                                    title = stringResource(R.string.work_mode),
                                    summary = activeMode?.let { mode ->
                                        stringResource(R.string.work_mode_entry_summary, stringResource(workModeNameRes(mode)))
                                    } ?: stringResource(R.string.work_mode_settings_row_summary),
                                    icon = Icons.Filled.SwapHoriz,
                                    onClick = actions.onOpenWorkMode,
                                )
                            }
                        }
                        // H3/H7：Lite 隐藏「设置项」（独立页；仅 Ultra 且未隐藏时可见）。
                        // Normal 的隐藏语义是 KSU stealth，不使用 Lite 寄生入口。
                        if (modePolicy.shows(SettingsSection.ULTRA_HIDDEN) && uiState.liteHiddenCard.showsRow) {
                            add {
                                dev.maplesu.manager.workmode.ui.LiteHiddenEntry(
                                    ui = uiState.liteHiddenCard,
                                    onClick = actions.onOpenLiteHidden,
                                )
                            }
                        }
                    },
                )

                // Ultra 专属：MapleSU_Ultra工作环境（panel 为 null 时说明未获许可，连组合都不发生）。
                AnimatedVisibility(
                    visible = ultraPanel != null,
                    enter = expandVertically(tween(160), expandFrom = Alignment.Top) + fadeIn(tween(120)),
                    exit = ExitTransition.None,
                ) {
                    ultraPanel?.let { panel ->
                        SegmentedColumn(
                            modifier = GroupPadding,
                            content = buildList {
                                add { UltraEnvironmentRowMaterial(panel) }
                            },
                        )
                    }
                }

                if (uiState.jailbreakSelinuxHide.visible && !uiState.hiddenMasked) {
                    SegmentedColumn(modifier = GroupPadding) {
                        item(key = "jailbreak-selinux-hide") {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.VisibilityOff,
                                title = stringResource(R.string.jailbreak_selinux_hide),
                                summary = stringResource(R.string.jailbreak_selinux_hide_summary) + "\n" +
                                    stringResource(uiState.jailbreakSelinuxHide.statusRes()),
                                checked = uiState.jailbreakSelinuxHide.requested,
                                onCheckedChange = actions.onSetJailbreakSelinuxHide,
                            )
                        }
                    }
                }

                if (modePolicy.shows(SettingsSection.ULTRA_MODULE_POLICY) && !uiState.hiddenMasked) {
                    val policy = uiState.ultraModulePolicy
                    SegmentedColumn(
                        modifier = GroupPadding,
                    ) {
                            item(key = "module-policy-load") {
                                SegmentedSwitchItem(
                                    icon = Icons.Filled.Shield,
                                    title = stringResource(R.string.ultra_module_policy_load),
                                    summary = stringResource(R.string.ultra_module_policy_load_summary) +
                                        policy.statusRes()?.let { "\n" + stringResource(it) }.orEmpty(),
                                    checked = policy.loadRequested, enabled = policy.operable,
                                    onCheckedChange = actions.onSetUltraModulePolicyLoad,
                                )
                            }
                            item(key = "module-policy-hide", visible = policy.loadRequested) {
                                    SegmentedSwitchItem(
                                        icon = Icons.Filled.VisibilityOff,
                                        title = stringResource(R.string.ultra_module_policy_hide),
                                        summary = stringResource(R.string.ultra_module_policy_hide_summary) +
                                            if (policy.hideNeedsRiskConfirmation) "\n" +
                                                stringResource(R.string.ultra_module_policy_hide_try)
                                            else if (policy.hideOperable) "" else "\n" +
                                                stringResource(policy.hideUnavailableRes()),
                                        checked = policy.hideRequested, enabled = policy.hideOperable,
                                        onCheckedChange = actions.onSetUltraModulePolicyHide,
                                    )
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
                SegmentedColumn(
                    modifier = GroupPadding,
                    content = buildList {
                        if (modePolicy.shows(SettingsSection.SU_COMPAT) && uiState.managerValid) {
                            add { SuCompatRowMaterial(uiState, actions) }
                        }
                        ksuRows.forEach { row -> add { KsuSwitchRowMaterial(row, uiState, actions) } }
                    },
                )

                // 通用：检查模块更新 + 软重启 + WebView 调试（两模式都消费）。
                SegmentedColumn(
                    modifier = GroupPadding,
                    content = buildList {
                        if (modePolicy.shows(SettingsSection.MODULE_UPDATE) && uiState.managerValid) {
                            add {
                                SegmentedSwitchItem(
                                    icon = Icons.Filled.SystemUpdateAlt,
                                    title = stringResource(R.string.settings_module_check_update),
                                    summary = stringResource(R.string.settings_check_update_summary),
                                    checked = uiState.checkModuleUpdate,
                                    onCheckedChange = actions.onSetCheckModuleUpdate,
                                )
                            }
                        }
                        if (modePolicy.shows(SettingsSection.GENERAL_TUNING) && uiState.managerValid) {
                            add {
                                SegmentedSwitchItem(
                                    icon = Icons.Filled.RestartAlt,
                                    title = stringResource(R.string.settings_soft_reboot),
                                    summary = stringResource(R.string.settings_soft_reboot_summary),
                                    checked = uiState.useSoftReboot,
                                    onCheckedChange = actions.onSetUseSoftReboot,
                                )
                            }
                            add {
                                SegmentedSwitchItem(
                                    icon = Icons.Filled.DeveloperMode,
                                    title = stringResource(R.string.enable_web_debugging),
                                    summary = stringResource(R.string.enable_web_debugging_summary),
                                    checked = uiState.enableWebDebugging,
                                    onCheckedChange = actions.onSetEnableWebDebugging,
                                )
                            }
                        }
                    },
                )

                // Normal（KSU）专属：App Profile 模板入口 + 内核形态只读行。
                SegmentedColumn(
                    modifier = GroupPadding,
                    content = buildList {
                        if (modePolicy.shows(SettingsSection.KSU_APP_PROFILE)) {
                            add {
                                ArrowRowMaterial(
                                    title = stringResource(R.string.settings_profile_template),
                                    summary = stringResource(R.string.settings_profile_template_summary),
                                    icon = Icons.Filled.Person,
                                    onClick = actions.onOpenProfileTemplate,
                                )
                            }
                        }
                        if (modePolicy.shows(SettingsSection.KSU_KERNEL_MODE)) {
                            add {
                                SegmentedListItem(
                                    headlineContent = { Text(stringResource(R.string.settings_kernel_mode)) },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                if (uiState.isLkmMode) {
                                                    R.string.settings_kernel_mode_lkm
                                                } else {
                                                    R.string.settings_kernel_mode_builtin
                                                },
                                            ),
                                        )
                                    },
                                    leadingContent = { Icon(Icons.Filled.Memory, null) },
                                )
                            }
                        }
                    },
                )

                // W18 三卡（Normal 专属；能力门控 + 诚实降级，见 workmode/ui/SettingsBFeatures.kt）。
                // 模式由 SettingsModePolicy 裁决；Ultra 下 bFeatures 恒 NONE，且 VM 不读 KSU 状态。
                val bf = uiState.bFeatures
                SegmentedColumn(
                    modifier = GroupPadding,
                    content = buildList {
                        if (modePolicy.shows(SettingsSection.KSU_STEALTH) && bf.stealth.show) {
                            add { StealthRowMaterial(uiState, actions) }
                        }
                        if (modePolicy.shows(SettingsSection.KSU_APP_LOCK) && bf.appLock.show) {
                            add {
                                SegmentedListItem(
                                    headlineContent = { Text(stringResource(R.string.work_mode_applock_title)) },
                                    supportingContent = { Text(stringResource(R.string.work_mode_applock_unavailable)) },
                                    leadingContent = { Icon(Icons.Filled.Lock, null) },
                                )
                            }
                        }
                        if (modePolicy.shows(SettingsSection.KSU_WEB_ADMIN) && bf.webAdmin.show) {
                            add {
                                if (bf.webAdmin.operable) {
                                    SegmentedSwitchItem(
                                        icon = Icons.Filled.Language,
                                        title = stringResource(R.string.work_mode_webadmin_title),
                                        summary = stringResource(R.string.work_mode_webadmin_summary),
                                        checked = bf.webAdmin.enabled,
                                        onCheckedChange = actions.onSetWebAdminEnabled,
                                    )
                                } else {
                                    SegmentedListItem(
                                        headlineContent = { Text(stringResource(R.string.work_mode_webadmin_title)) },
                                        supportingContent = { Text(stringResource(R.string.work_mode_webadmin_unavailable)) },
                                        leadingContent = { Icon(Icons.Filled.Language, null) },
                                    )
                                }
                            }
                            if (bf.webAdmin.operable) {
                                add {
                                    ArrowRowMaterial(
                                        title = stringResource(R.string.work_mode_webadmin_copy),
                                        summary = null,
                                        icon = Icons.Filled.Language,
                                        onClick = actions.onCopyWebAdminUrl,
                                    )
                                }
                                add {
                                    ArrowRowMaterial(
                                        title = stringResource(R.string.work_mode_webadmin_open),
                                        summary = null,
                                        icon = Icons.Filled.Language,
                                        onClick = actions.onOpenWebAdminUrl,
                                    )
                                }
                            }
                        }
                    },
                )

                // 卸载 + 日志 + 关于：与 KSU 一样放在最后。
                val showNormalUninstall = modePolicy.shows(SettingsSection.KSU_UNINSTALL) &&
                    uiState.managerValid && !uiState.hiddenMasked
                SegmentedColumn(
                    modifier = GroupPadding,
                    content = buildList {
                        ultraPanel?.let { panel -> add { UltraUninstallRowMaterial(panel) } }
                        if (showNormalUninstall) {
                            add {
                                ArrowRowMaterial(
                                    title = stringResource(R.string.uninstall),
                                    summary = null,
                                    icon = Icons.Filled.Delete,
                                    onClick = { showUninstall = true },
                                )
                            }
                        }
                        if (modePolicy.shows(SettingsSection.LOG_AND_ABOUT)) {
                            add {
                                ArrowRowMaterial(
                                    title = stringResource(R.string.send_log),
                                    summary = null,
                                    icon = Icons.Filled.BugReport,
                                    onClick = { showBottomSheet = true },
                                )
                            }
                            add {
                                ArrowRowMaterial(
                                    title = stringResource(R.string.about),
                                    summary = null,
                                    icon = Icons.Filled.Info,
                                    onClick = actions.onOpenAbout,
                                )
                            }
                            add {
                                ArrowRowMaterial(
                                    title = stringResource(R.string.iqoo_root_entry),
                                    summary = stringResource(R.string.iqoo_root_entry_summary),
                                    icon = Icons.Filled.Info,
                                    onClick = actions.onOpenIqooRoot,
                                )
                            }
                        }
                    },
                )

                if (showNormalUninstall) {
                    UninstallDialog(show = showUninstall, onDismissRequest = { showUninstall = false })
                }
                if (showBottomSheet) {
                    SendLogBottomSheet(
                        onDismiss = { showBottomSheet = false },
                        snackbarHostState = snackBarHost,
                    )
                }
            }
            Spacer(modifier = Modifier.height(bottomInnerPadding + 8.dp))
        }
    }
}

@Composable
private fun ArrowRowMaterial(title: String, summary: String?, icon: ImageVector, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { Icon(icon, title) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
}

@Composable
private fun SuCompatRowMaterial(uiState: SettingsUiState, actions: SettingsScreenActions) {
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
    SegmentedDropdownItem(
        icon = Icons.Filled.AdminPanelSettings,
        title = stringResource(R.string.settings_sucompat),
        summary = summary,
        items = items,
        enabled = uiState.suCompatStatus == "supported",
        selectedIndex = if (uiState.isSuEnabled) 0 else 1,
        onItemSelected = { actions.onSetSuCompatMode(if (it == 0) 0 else 2) },
    )
}

@Composable
private fun KsuSwitchRowMaterial(row: KsuRowId, uiState: SettingsUiState, actions: SettingsScreenActions) {
    when (row) {
        KsuRowId.KERNEL_UMOUNT -> SegmentedSwitchItem(
            icon = Icons.Filled.FolderOff,
            title = stringResource(R.string.settings_kernel_umount),
            summary = stringResource(R.string.settings_kernel_umount_summary),
            checked = uiState.isKernelUmountEnabled,
            onCheckedChange = actions.onSetKernelUmountEnabled,
        )

        KsuRowId.SELINUX_HIDE -> SegmentedSwitchItem(
            icon = Icons.Filled.Shield,
            title = stringResource(R.string.settings_selinux_hide),
            summary = stringResource(R.string.settings_selinux_hide_summary),
            checked = uiState.isSelinuxHideEnabled,
            onCheckedChange = actions.onSetSelinuxHideEnabled,
        )

        KsuRowId.SULOG -> SegmentedSwitchItem(
            icon = Icons.Filled.History,
            title = stringResource(R.string.settings_sulog),
            summary = stringResource(R.string.settings_sulog_summary),
            checked = uiState.isSulogEnabled,
            onCheckedChange = actions.onSetSulogEnabled,
        )

        KsuRowId.ADB_ROOT -> SegmentedSwitchItem(
            icon = Icons.Filled.Adb,
            title = stringResource(R.string.settings_adb_root),
            summary = stringResource(R.string.settings_adb_root_summary),
            checked = uiState.isAdbRootEnabled,
            onCheckedChange = actions.onSetAdbRootEnabled,
        )

        KsuRowId.SHELL_SU_ADB_ONLY -> SegmentedSwitchItem(
            icon = Icons.Filled.Terminal,
            title = stringResource(R.string.settings_shell_su_adb_only),
            summary = stringResource(R.string.settings_shell_su_adb_only_summary),
            checked = uiState.isShellSuAdbOnlyEnabled,
            onCheckedChange = actions.onSetShellSuAdbOnlyEnabled,
        )

        KsuRowId.UMOUNT_MODULES_DEFAULT -> SegmentedSwitchItem(
            icon = Icons.Filled.Extension,
            title = stringResource(R.string.settings_umount_modules_default),
            summary = stringResource(R.string.settings_umount_modules_default_summary),
            checked = uiState.isDefaultUmountModules,
            onCheckedChange = actions.onSetDefaultUmountModules,
        )
    }
}

@Composable
private fun StealthRowMaterial(uiState: SettingsUiState, actions: SettingsScreenActions) {
    val card = uiState.bFeatures.stealth
    // 状态行只陈述事实：只有明确 ON 才说「已开启」；UNKNOWN 必须说「状态未知（读取失败）」。
    val status = stringResource(SettingsStatusCopy.statusRes(settingsStatusOf(card)))
    if (card.operable && !card.enabled) {
        ArrowRowMaterial(
            title = stringResource(R.string.work_mode_stealth_title),
            summary = stringResource(R.string.normal_hidden_entry_summary),
            icon = Icons.Filled.VisibilityOff,
            onClick = actions.onOpenNormalHidden,
        )
    } else if (card.operable) {
        SegmentedSwitchItem(
            icon = Icons.Filled.VisibilityOff,
            title = stringResource(R.string.work_mode_stealth_title),
            summary = status + " · " + stringResource(R.string.work_mode_stealth_summary),
            checked = card.enabled,
            onCheckedChange = actions.onSetStealthEnabled,
        )
    } else {
        SegmentedListItem(
            headlineContent = { Text(stringResource(R.string.work_mode_stealth_title)) },
            supportingContent = {
                Text(status + " · " + stringResource(R.string.work_mode_stealth_unavailable))
            },
            leadingContent = { Icon(Icons.Filled.VisibilityOff, null) },
        )
    }
}

/**
 * 「界面与外观」子页内容。这一段以前在 Material 侧**完全缺失**(审计:Material 19 项 vs
 * Miuix 48 项)，2026-09-16 补齐；配置键 / 默认值 / 滑杆换算都与 Miuix 设置页一一对应，
 * 改的是同一份 prefs，来回切换界面风格不丢设置。
 *
 * 初值来自 [SettingsUiState.appearance]（ViewModel 已读好），这里只持有本地编辑状态。
 */
@Composable
private fun ColumnScope.AppearanceBodyMaterial(uiState: SettingsUiState, actions: SettingsScreenActions) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        AppearanceSettingsBody(uiState, actions)
    }
}

@Composable
private fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    LargeFlexibleTopAppBar(
        title = { Text(title) },
        navigationIcon = { onBack?.let { TopBarBackButton(onClick = it) } },
        colors = expressiveTopAppBarColors(),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior,
    )
}
