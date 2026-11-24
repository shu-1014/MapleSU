package com.maplesu.core.ui.screen.settings

import androidx.compose.runtime.Immutable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.maplesu.core.ui.UiMode

@Immutable
data class SettingsUiState(
    val uiMode: String = UiMode.DEFAULT_VALUE,
    /**
     * 工作模式可见性/读取许可的唯一裁决（2026-10-31 模式独立性）。
     * 默认 [dev.maplesu.manager.workmode.ui.SettingsModePolicy.forMode]`(null)` = 只有通用项，
     * 任何读取失败/未刷新路径都是安全默认值。
     */
    val modePolicy: dev.maplesu.manager.workmode.ui.SettingsModePolicy =
        dev.maplesu.manager.workmode.ui.SettingsModePolicy.forMode(null),
    val checkUpdate: Boolean = true,
    val checkModuleUpdate: Boolean = true,
    val themeMode: Int = 0,
    val miuixMonet: Boolean = false,
    val keyColor: Int = 0,
    val colorStyle: String = PaletteStyle.TonalSpot.name,
    val colorSpec: String = ColorSpec.SpecVersion.SPEC_2025.name,
    val enablePredictiveBack: Boolean = false,
    val enableSwipeDismiss: Boolean = true,
    val pagerInterceptionMode: Int = 1,
    val enableBlur: Boolean = true,
    val enableFloatingBottomBar: Boolean = false,
    val enableFloatingBottomBarBlur: Boolean = false,
    val enableNavigationBadge: Boolean = true,
    val pageScale: Float = 1.0f,
    val moduleDescriptionMaxLines: Int = 4,
    val enableWebDebugging: Boolean = false,

    // Su Compat
    val suCompatStatus: String = "",
    val suCompatMode: Int = 0, // 0: enable default, 1: disable until reboot, 2: disable always
    val isSuEnabled: Boolean = false,

    // Kernel Umount
    val kernelUmountStatus: String = "",
    val isKernelUmountEnabled: Boolean = false,

    // SELinux Hide
    val selinuxHideStatus: String = "",
    val isSelinuxHideEnabled: Boolean = false,
    val jailbreakSelinuxHide: dev.maplesu.manager.jailbreak.JailbreakSelinuxHideState =
        dev.maplesu.manager.jailbreak.JailbreakSelinuxHideState(),
    val ultraModulePolicy: dev.maplesu.manager.modules.UltraModulePolicyState =
        dev.maplesu.manager.modules.UltraModulePolicyState(),

    // SU Log
    val sulogStatus: String = "",
    val isSulogEnabled: Boolean = false,

    // Umount Modules
    val isDefaultUmountModules: Boolean = false,

    // ADB Root
    val adbRootStatus: String = "",
    val isAdbRootEnabled: Boolean = false,

    // Shell SU ADB Only
    val shellSuAdbOnlyStatus: String = "",
    val isShellSuAdbOnlyEnabled: Boolean = false,

    val isLkmMode: Boolean = false,
    val isLateLoadMode: Boolean = false,

    // Auto Jailbreak
    val autoJailbreak: Boolean = false,

    // Soft Reboot
    val useSoftReboot: Boolean = false,

    // W18 三卡（B 专属；能力门控 + 诚实降级，见 workmode/ui/SettingsBFeatures.kt）
    val bFeatures: dev.maplesu.manager.workmode.ui.BFeatureCards =
        dev.maplesu.manager.workmode.ui.BFeatureCards.NONE,

    // H3：Lite 模式隐藏（A 专属；默认 OFF，与 KSU stealth 独立）
    val liteHiddenCard: dev.maplesu.manager.workmode.ui.LiteHiddenCardUi =
        dev.maplesu.manager.workmode.ui.LiteHiddenCardUi.HIDDEN,

    /** Normal 真卸载确认流程；访问地址不进入 UI 快照。 */
    val normalHidden: NormalHiddenUi = NormalHiddenUi(),

    /** H7：WebUI 自检入口可见性（隐藏生效后整体消失）。 */
    val showWebUiSelfCheck: Boolean = false,

    /**
     * 管理器可用（等价旧的组合期 `KsuIsValid`）。放在状态里后设置页不在组合期碰 JNI，
     * 由 ViewModel 在后台读取，首帧取缓存快照。
     */
    val managerValid: Boolean = false,

    /**
     * 隐藏/隐身是否按生效处理（判据同 `currentWorkModeHiddenMask`）。默认 true：
     * 还没算出来时不露出任何带模式字样的卡片（不变量 §2.1）。
     */
    val hiddenMasked: Boolean = true,

    /** 工作模式行可见性（04-ui-contract §3.4/§3.5）：页面只消费结果。 */
    val workModeEntry: dev.maplesu.manager.workmode.ui.WorkModeEntryPolicy =
        dev.maplesu.manager.workmode.ui.WorkModeEntryPolicy.NONE,

    /** 能力位快照（KSU 六行等）。 */
    val capabilities: dev.maplesu.manager.workmode.ModeCapabilities =
        dev.maplesu.manager.workmode.ModeCapabilities.NONE,

    /** 「界面与外观」子页的本地设置快照；组合期只消费，不读 prefs。 */
    val appearance: AppearancePrefsSnapshot = AppearancePrefsSnapshot(),
)

/**
 * 「界面与外观」子页用到的本地设置（配置键与默认值都在 `SettingsRepositoryImpl`）。
 * 子页只用它做初值，用户改动后本地 state 为准、同时写回 prefs。
 */
@Immutable
data class AppearancePrefsSnapshot(
    val homeLargeCard: Boolean = true,
    val snowEnabled: Boolean = false,
    val snowSize: Float = 1f,
    val snowSpeed: Float = 1f,
    val trollRainEnabled: Boolean = false,
    val trollRainSize: Float = 1f,
    val trollRainSpeed: Float = 1f,
    val gravityEnabled: Boolean = false,
    val gravityMode: Int = 0,
    val translucent: Boolean = false,
    val translucentAlpha: Float = 1f,
    val wallpaperDim: Float = 0.35f,
    val wallpaperBlur: Float = 0f,
    val decorationAnchor: Int = 0,
    val decorationDim: Float = 0.55f,
    val decorationHideText: Boolean = false,
    val decorationZoom: Float = 1f,
    val navIconCircle: Boolean = false,
    val hasDecoration: Boolean = false,
    val wallpaperKind: String = "none",
    val wallpaperLandKind: String = "none",
    val wallpaperBroken: Boolean = false,
    val wallpaperLandBroken: Boolean = false,
    val customNavIcons: Set<String> = emptySet(),
)

@Immutable
data class SettingsScreenActions(
    val onSetCheckUpdate: (Boolean) -> Unit,
    val onSetCheckModuleUpdate: (Boolean) -> Unit,
    val onOpenTheme: () -> Unit,
    /** 打开「界面与外观」子页面（所有个性化设置都收在那里）。 */
    val onOpenAppearance: () -> Unit,
    /** 打开「自定义各UI图片」子页面（状态卡图片 + 四个导航图标）。 */
    val onOpenImages: () -> Unit,
    val onSetUiModeIndex: (Int) -> Unit,
    val onOpenProfileTemplate: () -> Unit,
    val onSetSuCompatMode: (Int) -> Unit,
    val onSetKernelUmountEnabled: (Boolean) -> Unit,
    val onSetSelinuxHideEnabled: (Boolean) -> Unit,
    val onSetSulogEnabled: (Boolean) -> Unit,
    val onSetAdbRootEnabled: (Boolean) -> Unit,
    val onSetShellSuAdbOnlyEnabled: (Boolean) -> Unit,
    val onSetDefaultUmountModules: (Boolean) -> Unit,
    val onSetEnableWebDebugging: (Boolean) -> Unit,
    val onSetAutoJailbreak: (Boolean) -> Unit,
    val onSetUseSoftReboot: (Boolean) -> Unit,
    val onOpenAbout: () -> Unit,
    val onOpenWallpaper: () -> Unit = {},
    val onOpenThemeStore: () -> Unit = {},
    val onOpenFont: () -> Unit = {},
    val onOpenLayout: () -> Unit = {},
    /** 打开工作模式选择框（§3.2；04-ui-contract §2.2 入口 C）。 */
    val onOpenWorkMode: () -> Unit = {},
    /** W18 隐身开关（B + 内核支持才可操作）。 */
    val onSetStealthEnabled: (Boolean) -> Unit = {},
    /** Normal：准备恢复链接并打开卸载确认，点击本身不启用隐身。 */
    val onOpenNormalHidden: () -> Unit = {},
    /** W18 网页管理器开关（B 可用）。 */
    val onSetWebAdminEnabled: (Boolean) -> Unit = {},
    /** W18 网页管理器：复制地址 / 浏览器打开（地址含 token，只走内存）。 */
    val onCopyWebAdminUrl: () -> Unit = {},
    val onOpenWebAdminUrl: () -> Unit = {},
    /** H3/H7：打开独立「隐藏」设置页（仅 LITE；隐藏后该入口整体消失）。 */
    val onOpenLiteHidden: () -> Unit = {},
    /** H7：打开 WebUI 自检页（内部验证；隐藏后入口消失）。 */
    val onOpenWebUiSelfCheck: () -> Unit = {},
    val onSetUltraModulePolicyLoad: (Boolean) -> Unit = {},
    val onSetUltraModulePolicyHide: (Boolean) -> Unit = {},
    val onSetJailbreakSelinuxHide: (Boolean) -> Unit = {},
    /** iQOO Neo 5（PD2055）专用提权入口，独立 Activity。 */
    val onOpenIqooRoot: () -> Unit = {},
)
