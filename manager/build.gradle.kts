// UI and dependency catalog: 7kimisu dafd0a96 (GPL-3.0-or-later).
import com.google.protobuf.gradle.id
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.security.MessageDigest

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
    id("kotlin-parcelize")
}

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    generateProtoTasks {
        ofNonTest().forEach { task ->
            task.builtins {
                id("java") { option("lite") }
                id("kotlin") { option("lite") }
            }
        }
    }
}

// --- 5.0.5 随包 Ultra SDK 构建身份（配置期读取，缺失即降级，构建不得失败） -------------
// 身份清单优先级：runtime/maplesu-sdk/SDK-RELEASE.json（发布清单，导入脚本生成）
// → evidence/ultra-sdk-505/sdk/sdk-build.json（构建清单）。两处都缺/不可解析时用默认值。
// 读取结果注入 BuildConfig，与 runtime/maplesu-sdk/SDK-IDENTITY.cmake 注入 C++ 的宏
// 同源（都由 sdk-build.json 派生），因此 Kotlin 侧与 getSdkBuildIdentity 回执可逐字段对齐。
// 字段名两种清单形状不同（SCHEMA.md §5/§6），这里两种都接受；主文件缺某字段时由次文件补。
data class UltraSdkIdentity(
    val buildId: String = "unknown",
    val sdkVersion: String = "unknown",
    val helperSize: Long = -1L,
    val sdkSha256: String = "unknown",
    val helperSha256: String = "unknown",
    val helperBuildId: String = "unknown",
    val keySlotOffset: Long = -1L,
    val source: String = "defaults",
)

fun ultraSdkJson(file: File): Map<String, Any?>? = try {
    if (!file.isFile) null else {
        @Suppress("UNCHECKED_CAST")
        JsonSlurper().parse(file) as? Map<String, Any?>
    }
} catch (t: Throwable) {
    // 身份清单损坏同样不能失败：调用方退回默认值，来源记为该文件（便于定位坏文件）。
    null
}

fun Map<String, Any?>.ultraSdkObject(key: String): Map<String, Any?>? =
    (this[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }

fun Map<String, Any?>.ultraSdkText(key: String): String? =
    (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

fun Map<String, Any?>.ultraSdkNumber(key: String): Long? = when (val value = this[key]) {
    is Number -> value.toLong()
    is String -> value.trim().toLongOrNull()
    else -> null
}

fun ultraSdkJavaString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val ultraSdkDirectory = providers.gradleProperty("maplesuUltraSdkDirectory").orNull
    ?.let { rootProject.file(it) } ?: rootProject.file("runtime/maplesu-sdk")
val ultraSdkIdentityManifest = ultraSdkJson(ultraSdkDirectory.resolve("SDK-RELEASE.json"))
val policyBootBackend = ultraSdkIdentityManifest?.ultraSdkObject("environmentPolicyBoot")?.ultraSdkObject("backend")
val policyBootExpectedSha256 = policyBootBackend?.ultraSdkText("sha256").orEmpty()
val policyBootExpectedBytes = policyBootBackend?.ultraSdkNumber("size") ?: 0L
require(policyBootBackend == null || policyBootExpectedSha256.matches(Regex("[0-9a-f]{64}")) && policyBootExpectedBytes in 64L..33_554_432L &&
    policyBootBackend.ultraSdkText("path") != null) {
    "Boot backend identity is incomplete"
}
if (providers.gradleProperty("maplesuUltraSdkDirectory").isPresent) {
    require(ultraSdkIdentityManifest != null && ultraSdkDirectory.resolve("SDK-IDENTITY.cmake").isFile) {
        "An explicit SDK directory requires its verified release and generated identity"
    }
}
// schema §5 要求 sdk-build.json 在 evidence/ultra-sdk-505/sdk/ 与 evidence/ultra-sdk-505/ 各有一份
// 字节一致的副本：SDK 重建期间 sdk/ 会被清空重建，根下副本保证配置期仍能取到身份。
val ultraSdkBuildManifestPath = listOf(
    "evidence/ultra-sdk-505/sdk/sdk-build.json",
    "evidence/ultra-sdk-505/sdk-build.json",
).firstOrNull { rootProject.file(it).isFile }
val ultraSdkBuildManifest = ultraSdkBuildManifestPath?.let { ultraSdkJson(rootProject.file(it)) }
val ultraSdkIdentity = UltraSdkIdentity(
    buildId = ultraSdkIdentityManifest?.ultraSdkText("sdkBuildId")
        ?: ultraSdkBuildManifest?.ultraSdkText("sdkBuildId") ?: "unknown",
    sdkVersion = ultraSdkIdentityManifest?.ultraSdkText("sdkVersion")
        ?: ultraSdkBuildManifest?.ultraSdkText("sdkVersion") ?: "unknown",
    helperSize = ultraSdkIdentityManifest?.ultraSdkObject("helper")?.ultraSdkNumber("size")
        ?: ultraSdkBuildManifest?.ultraSdkObject("helper")?.ultraSdkNumber("size") ?: -1L,
    sdkSha256 = ultraSdkIdentityManifest?.ultraSdkObject("archive")?.ultraSdkText("sha256")
        ?: ultraSdkBuildManifest?.ultraSdkObject("artifacts")?.ultraSdkObject("archive")?.ultraSdkText("sha256")
        ?: ultraSdkBuildManifest?.ultraSdkText("sdkSha256") ?: "unknown",
    helperSha256 = ultraSdkIdentityManifest?.ultraSdkObject("helper")?.ultraSdkText("normalizedSha256")
        ?: ultraSdkBuildManifest?.ultraSdkObject("helper")?.ultraSdkText("normalizedSha256")
        ?: ultraSdkIdentityManifest?.ultraSdkObject("helper")?.ultraSdkText("sha256")
        ?: ultraSdkBuildManifest?.ultraSdkObject("helper")?.ultraSdkText("sha256")
        ?: ultraSdkBuildManifest?.ultraSdkText("helperSha256") ?: "unknown",
    helperBuildId = ultraSdkIdentityManifest?.ultraSdkObject("helper")?.ultraSdkText("buildId")
        ?: ultraSdkBuildManifest?.ultraSdkObject("helper")?.ultraSdkText("buildId")
        ?: ultraSdkBuildManifest?.ultraSdkObject("bootstrap")?.ultraSdkText("elfBuildId")
        ?: ultraSdkBuildManifest?.ultraSdkObject("bootstrap")?.ultraSdkText("buildId") ?: "unknown",
    keySlotOffset = ultraSdkIdentityManifest?.ultraSdkObject("helper")?.ultraSdkNumber("keySlotOffset")
        ?: ultraSdkIdentityManifest?.ultraSdkNumber("keySlotOffset")
        ?: ultraSdkBuildManifest?.ultraSdkObject("helper")?.ultraSdkNumber("keySlotOffset")
        ?: ultraSdkBuildManifest?.ultraSdkObject("bootstrap")?.ultraSdkNumber("keySlotOffset")
        ?: ultraSdkBuildManifest?.ultraSdkNumber("keySlotOffset")
        ?: -1L,
    source = when {
        ultraSdkIdentityManifest != null -> ultraSdkDirectory.resolve("SDK-RELEASE.json").relativeTo(rootProject.projectDir).invariantSeparatorsPath
        ultraSdkBuildManifest != null -> ultraSdkBuildManifestPath ?: "defaults"
        else -> "defaults"
    },
)

// 5.0.5 验收开关：只有显式 -PmaplesuSdkAcceptanceRelease=true 才把 instrumentation 跑在
// release 变体上；默认（不加该属性）与本改动之前完全一致。
val acceptance = providers.gradleProperty("maplesuSdkAcceptanceRelease").orNull == "true"

android {
    namespace = "com.maplesu.core"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    buildToolsVersion = "37.0.0"
    // Normal's kernel APK verifier accepts v2 signatures and verity padding only.
    // AGP dependency metadata adds block 0x504b4453 and prevents manager recognition.
    dependenciesInfo { includeInApk = false }
    defaultConfig {
        applicationId = "dev.maplesu.manager"
        minSdk = 31
        targetSdk = 37
        versionCode = 50202
        versionName = "5.2.1-iqoo"
        buildConfigField("boolean", "IS_PR_BUILD", "false")
        // 5.0.5 随包构建身份：未导入 SDK 时全部为 unknown / -1，构建照常成功。
        buildConfigField("String", "ULTRA_SDK_BUILD_ID", ultraSdkJavaString(ultraSdkIdentity.buildId))
        buildConfigField(
            "String", "ULTRA_SDK_UPSTREAM_VERSION", ultraSdkJavaString(ultraSdkIdentity.sdkVersion))
        buildConfigField("String", "ULTRA_SDK_VERSION", ultraSdkJavaString(ultraSdkIdentity.sdkVersion))
        buildConfigField("long", "ULTRA_SDK_HELPER_SIZE", "${ultraSdkIdentity.helperSize}L")
        buildConfigField("String", "ULTRA_SDK_SHA256", ultraSdkJavaString(ultraSdkIdentity.sdkSha256))
        buildConfigField(
            "String", "ULTRA_SDK_HELPER_SHA256", ultraSdkJavaString(ultraSdkIdentity.helperSha256))
        buildConfigField(
            "String", "ULTRA_SDK_HELPER_BUILD_ID", ultraSdkJavaString(ultraSdkIdentity.helperBuildId))
        buildConfigField("long", "ULTRA_SDK_KEY_SLOT_OFFSET", "${ultraSdkIdentity.keySlotOffset}L")
        buildConfigField("String", "ULTRA_POLICY_BACKEND_SHA256", ultraSdkJavaString(policyBootExpectedSha256))
        buildConfigField("long", "ULTRA_POLICY_BACKEND_BYTES", "${policyBootExpectedBytes}L")
        buildConfigField(
            "String", "ULTRA_SDK_IDENTITY_SOURCE", ultraSdkJavaString(ultraSdkIdentity.source))
        testInstrumentationRunner = if (providers.gradleProperty("maplesuUiPresentationTests").orNull == "true" ||
            providers.gradleProperty("maplesuThemeIsolatedTests").orNull == "true")
            "com.maplesu.core.ui.PresentationTestRunner" else "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { aidl = true; buildConfig = true; resValues = true; compose = true }
    signingConfigs {
        val releaseStore = providers.environmentVariable("MAPLE_RELEASE_STORE_FILE").orNull
        if (!releaseStore.isNullOrBlank()) {
            create("mapleRelease") {
                storeFile = file(releaseStore)
                storePassword = providers.environmentVariable("MAPLE_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("MAPLE_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("MAPLE_RELEASE_KEY_PASSWORD").get()
                // Keep the APK signing block compatible with the Normal kernel parser.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = false
                enableV4Signing = false
            }
        }
    }
    buildTypes {
        debug {
            // Opt-in device test install: preserve the user's installed manager and backend data.
            if (providers.gradleProperty("maplesuThemeIsolatedTests").orNull == "true") {
                applicationIdSuffix = ".themetest"
            }
        }
        release {
            signingConfigs.findByName("mapleRelease")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles("proguard-android-test.pro")
        }
    }
    // 验收模式：instrumentation 跑在 release 变体上；不加属性时保持 AGP 默认（debug）。
    if (acceptance) {
        testBuildType = "release"
        buildTypes.getByName("release").proguardFiles("proguard-acceptance.pro")
    }
    androidResources {
        generateLocaleConfig = true
        // 2026-11-01 55-asset-slim：`noCompress += "xz"` 随 `assets/boot-stock/**` 一并删除
        // （本构建 assets 里已无任何 `.xz`）。若将来重新随包 xz 资产，必须把这条加回来，
        // 否则 AAPT2 会再 deflate 一遍；**绝不能**把 "img" 加进来（104 MiB 裸镜像会直接爆体积闸门）。
    }
    // Provider, lease and artefact Java live in their own tree so the jailbreak
    // provider work stays reviewable and independent of the runtime sources.
    sourceSets["main"].java.srcDir(rootProject.file("jailbreak-providers/android/src/main/java"))
    sourceSets["main"].java.srcDir(rootProject.file("third_party/t3-sdk/src/main/java"))
    if (providers.gradleProperty("maplesuThemeIsolatedTests").orNull == "true") {
        sourceSets["debug"].manifest.srcFile("src/themeTest/AndroidManifest.xml")
    }
    // Vendored upstream Ghostlock profile-core (byte-for-byte, see
    // jailbreak-providers/ghostlock/profile-core/vendored.json): the HOCON profile
    // must be turned into the typed wire v2 document the native decoder accepts.
    sourceSets["main"].kotlin.srcDir(
        rootProject.file("jailbreak-providers/ghostlock/profile-core/src/main/kotlin"))
    // Ghostlock OTA/local inputs and offsets.conf imports. The App delegates
    // image analysis to the retained native reference; helpers are host-tested.
    sourceSets["main"].kotlin.srcDir(
        rootProject.file("jailbreak-providers/ghostlock/extractor/src/main/kotlin"))
    sourceSets["test"].java.srcDir(rootProject.file("jailbreak-providers/android/src/test/java"))
    sourceSets["test"].kotlin.srcDir(
        rootProject.file("jailbreak-providers/ghostlock/extractor/src/test/kotlin"))
    // MapleSU 双工作模式：KSU 后端独立目录（W10/W11）。D5 要求「独立 sourceSet」，
    // 但 AGP 不把任意命名的 sourceSet 编进 variant，故使用单 sourceSet
    // 多目录方案并入 main；边界由 KsuSourceIsolationTest 机器校验（目录 + import 规则）。
    sourceSets["main"].kotlin.srcDir("src/ksu/java")
    sourceSets["main"].kotlin.srcDir(rootProject.file("jailbreak-providers/maplesu-93260/src/main/kotlin"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    lint {
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }
    packaging.dex.useLegacyPackaging = true
    packaging.jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols += "**/libmaplesu_runtime.so"
        keepDebugSymbols += "**/libmaplesu_su.so"
        keepDebugSymbols += "**/libmaplesu_patch.so"
        keepDebugSymbols += "**/libmaplesu_policy.so"
        keepDebugSymbols += "**/libmaplesu_policy_boot.so"
        // Parasite loader (hidden mode): the host's DT_NEEDED entry resolves to these
        // exact bytes and the activation receipt records their sha256.
        keepDebugSymbols += "**/libmaplesu_parasite.so"
        // Module tool host is published by digest and symlinked at deploy time;
        // stripping it would change the bytes the deployment receipt records.
        keepDebugSymbols += "**/libmaplesu_tools.so"
        // Provider entries are digest-bound by the packaged manifest; stripping
        // them would change the bytes the App verifies at runtime.
        keepDebugSymbols += "**/libdfroot.so"
        keepDebugSymbols += "**/libmaplesu_dirtyfrag_bootstrap.so"
        keepDebugSymbols += "**/libmaplesu_root_consumer.so"
        keepDebugSymbols += "**/libmaplesu_ghostlock_provider.so"
        keepDebugSymbols += "**/libmaplesu_ghostlock_extract.so"
        // iQOO Neo 5 提权的三个可执行文件：必须以 lib*.so 形式随包装，
        // 因为 /data/user/0 是 noexec，只有 nativeLibraryDir 才允许 exec。
        keepDebugSymbols += "**/libperfleak2.so"
        keepDebugSymbols += "**/libpd2055_exploit.so"
        keepDebugSymbols += "**/libkoreloc.so"
        // Ultra bridge: the imported SDK archive is prebuilt and its bytes are recorded in
        // runtime/maplesu-sdk/PROVENANCE.json. Stripping the bridge would change the
        // bytes the deployment receipt records, so keep them.
        keepDebugSymbols += "**/libmaplesu_ultra_bridge.so"
        // KSU JNI (W12d): built by third_party/kernelsu/scripts/build_jni.sh (NDK 26.1,
        // 16KB page). ksu-payload.json records their exact sha256, and the KSU facade is
        // validated against those bytes; AGP strip would change them, so keep them intact.
        keepDebugSymbols += "**/libmaplesu_ksu.so"
        keepDebugSymbols += "**/libksud_ksu.so"
        keepDebugSymbols += "**/libadbroot.so"
    }
}

// androidTest 签名（5.0.5 验收）：`testBuildType = "release"` 只决定测试组件按哪个变体
// 构建，装到设备上的测试 APK 还必须与受测 APK 同证书，否则安装会被
// INSTALL_FAILED_TEST_APP_SIGNATURE_MISMATCH 拒绝。AGP 对 application 模块没有
// `androidTest.signingConfig` 的 DSL 块，但 Variant API 暴露了 AndroidTest.signingConfig，
// 所以验收模式下把 release 的整份签名配置（现有 mapleRelease）复制给测试 APK。
// 没有 MAPLE_RELEASE_STORE_FILE 时不存在 mapleRelease 配置：此时不做任何改动，保持原行为
// （AGP 默认签名），失败只发生在真的缺密钥无法签 release 的时候。
val releaseSigningConfig = android.signingConfigs.findByName("mapleRelease")
if (acceptance && releaseSigningConfig != null) {
    androidComponents.onVariants { variant ->
        variant.androidTest?.signingConfig?.from(releaseSigningConfig)
    }
}

// Android executes this PIE from the package-owned nativeLibraryDir. Build the
// original userspace sources here rather than importing a research binary.
val runtimeSource = rootProject.layout.projectDirectory.dir("runtime")
val runtimeBuild = layout.buildDirectory.dir("maplesuRuntime")
val runtimeJni = layout.buildDirectory.dir("generated/maplesuRuntimeJni")
val runtimeSdk = androidComponents.sdkComponents.sdkDirectory
val runtimeToolSuffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
val configureMapleSuRuntime = tasks.register<Exec>("configureMapleSuRuntime") {
    inputs.file(runtimeSource.file("CMakeLists.txt"))
    inputs.property("ultraSdkDirectory", ultraSdkDirectory.absolutePath)
    inputs.file(ultraSdkDirectory.resolve("SDK-IDENTITY.cmake"))
    outputs.file(runtimeBuild.map { it.file("CMakeCache.txt") })
    doFirst {
        val sdk = runtimeSdk.get().asFile
        commandLine(
            sdk.resolve("cmake/3.22.1/bin/cmake$runtimeToolSuffix"),
            "-S", runtimeSource.asFile, "-B", runtimeBuild.get().asFile, "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=${sdk.resolve("cmake/3.22.1/bin/ninja$runtimeToolSuffix")}",
            "-DCMAKE_TOOLCHAIN_FILE=${sdk.resolve("ndk/26.1.10909125/build/cmake/android.toolchain.cmake")}",
            "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-31",
            "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
            "-DMAPLESU_ULTRA_SDK_DIRECTORY=${ultraSdkDirectory.absolutePath}",
        )
    }
}
val buildMapleSuRuntime = tasks.register<Exec>("buildMapleSuRuntime") {
    dependsOn(configureMapleSuRuntime)
    inputs.files(fileTree(runtimeSource) {
        include("src/**", "upstream/**", "CMakeLists.txt")
        // Fixed prebuilt Ultra SDK import plus the production bridge that links it.
        include("maplesu-sdk/**", "ultra/**")
        exclude("maplesu-sdk/sdk/build/**")
        // IDA analysis databases are not runtime build inputs.
        exclude("**/*.id0", "**/*.id1", "**/*.id2", "**/*.nam", "**/*.til", "**/*.i64", "**/*.i64.crash-*/**")
    })
    inputs.files(fileTree(ultraSdkDirectory) { include("SDK-IDENTITY.cmake", "SDK-RELEASE.json", "lib/**", "include/**") })
    outputs.files(runtimeBuild.map { it.file("maplesu-runtime") }, runtimeBuild.map { it.file("maplesu-su") },
        runtimeBuild.map { it.file("libmaplesu_bootstrap.so") },
        runtimeBuild.map { it.file("libmaplesu_parasite.so") },
        runtimeBuild.map { it.file("libmaplesu_ultra_bridge.so") })
    doFirst {
        commandLine(runtimeSdk.get().asFile.resolve("cmake/3.22.1/bin/cmake$runtimeToolSuffix"),
            "--build", runtimeBuild.get().asFile, "-j", "6")
    }
}
val patchSource = rootProject.layout.projectDirectory.dir("patch-engine")
val patchBuild = layout.buildDirectory.dir("maplesuPatch")
val configureMapleSuPatch = tasks.register<Exec>("configureMapleSuPatch") {
    inputs.files(patchSource.file("CMakeLists.txt"), patchSource.file("upstream-sources.cmake"))
    outputs.file(patchBuild.map { it.file("CMakeCache.txt") })
    doFirst {
        val sdk = runtimeSdk.get().asFile
        commandLine(
            sdk.resolve("cmake/3.22.1/bin/cmake$runtimeToolSuffix"),
            "-S", patchSource.asFile, "-B", patchBuild.get().asFile, "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=${sdk.resolve("cmake/3.22.1/bin/ninja$runtimeToolSuffix")}",
            "-DCMAKE_TOOLCHAIN_FILE=${sdk.resolve("ndk/26.1.10909125/build/cmake/android.toolchain.cmake")}",
            "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-31",
            "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
        )
    }
}
val buildMapleSuPatch = tasks.register<Exec>("buildMapleSuPatch") {
    dependsOn(configureMapleSuPatch)
    inputs.files(fileTree(patchSource) { include("src/**", "upstream/**", "*.cmake", "CMakeLists.txt") })
    outputs.file(patchBuild.map { it.file("maplesu-kernel-patch") })
    doFirst {
        commandLine(runtimeSdk.get().asFile.resolve("cmake/3.22.1/bin/cmake$runtimeToolSuffix"),
            "--build", patchBuild.get().asFile, "--target", "maplesu-kernel-patch", "-j", "6")
    }
}
val policySource = rootProject.layout.projectDirectory.dir("module-policy")
val policyBuild = policySource.dir("build/arm64-v8a")
val policyBootBundleManifest = ultraSdkIdentityManifest?.ultraSdkObject("environmentPolicyBoot")
    ?.ultraSdkObject("delivery")?.ultraSdkObject("bundleManifest")?.ultraSdkText("path")?.let { rootProject.file(it) }
val policyHookBundles = providers.gradleProperty("maplesuPolicyHookBundles")
    .map { rootProject.file(it) }
    .getOrElse(policyBootBundleManifest?.let { it.resolveSibling("${it.nameWithoutExtension}.cpp") }
        ?: rootProject.file("runtime/ultra/policy/hook-bundles/policy-bundles.cpp"))
if (policyBootBundleManifest != null) {
    val expected = ultraSdkJson(policyBootBundleManifest)?.ultraSdkText("generatedSourceSha256")
    val actual = policyHookBundles.takeIf { it.isFile }?.readBytes()?.let {
        MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) }
    }
    require(expected != null && actual == expected) {
        "The policy tool and SDK backend require the same verified bundle"
    }
}
val buildModulePolicy = tasks.register<Exec>("buildModulePolicy") {
    inputs.files(fileTree(policySource) {
        include("src/**", "tests/**", "upstream/**", "derived/**", "CMakeLists.txt")
    })
    inputs.files(rootProject.file("tools/build_module_policy.py"), rootProject.file("tools/native_toolchain.py"))
    inputs.files(rootProject.fileTree("runtime/ultra/policy") { include("**/*.h", "**/*.cpp", "**/*.c", "**/*.S", "**/*.json", "**/Makefile") })
    inputs.files(rootProject.fileTree("jailbreak-providers/root-loader") {
        include("policy_hook_abi.h", "boot_sdk_abi.h", "selinux_hide_abi.h")
    })
    inputs.files(policyHookBundles, policyHookBundles.resolveSibling("${policyHookBundles.nameWithoutExtension}.json"))
    inputs.files(rootProject.fileTree("third_party/kernelsu/applied/uapi") { include("*.h") })
    outputs.files(policyBuild.file("maplesu-policy"), policyBuild.file("build.json"))
    // Validate the pinned checkout and retained upstream hashes on every build.
    outputs.upToDateWhen { false }
    workingDir(rootProject.projectDir)
    doFirst {
        val sdk = runtimeSdk.get().asFile
        val python = providers.gradleProperty("maplesuPython")
            .orElse(providers.environmentVariable("MAPLESU_PYTHON")).getOrElse("python")
        commandLine(python, rootProject.file("tools/build_module_policy.py"),
            "--ndk", sdk.resolve("ndk/26.1.10909125"),
            "--cmake", sdk.resolve("cmake/3.22.1/bin/cmake$runtimeToolSuffix"),
            "--offline", "--hook-bundles", policyHookBundles)
    }
}
// MapleSU module tool host, built from the pinned ksu_props attribute library.
// Shape mirrors the KSU userspace contract: one host executable, with `resetprop`
// published as a symlink to it (assets.rs:26-28) and dispatched by argv[0]
// (cli.rs:521-524). The resetprop CLI source is included from the pinned checkout
// rather than reimplemented, so flags/exit codes stay upstream.
val moduleToolsProject = rootProject.layout.projectDirectory.dir("module-tools")
val moduleToolsBuild = layout.buildDirectory.dir("moduleTools")
val buildModuleTools = tasks.register<Exec>("buildModuleTools") {
    inputs.files(fileTree(moduleToolsProject) { include("src/**", "Cargo.toml", "Cargo.lock") })
    inputs.files(fileTree(rootProject.layout.projectDirectory.dir("third_party/ksu_props")) {
        include("SOURCE.json", "src/Cargo.toml", "src/Cargo.lock", "src/crates/**/Cargo.toml", "src/crates/**/src/**",
            "src/tools/resetprop/src/**")
    })
    inputs.files(rootProject.file("tools/build_module_tools.py"), rootProject.file("tools/native_toolchain.py"))
    outputs.files(moduleToolsBuild.map { it.file("native/maplesu-tools") }, moduleToolsBuild.map { it.file("build.json") })
    // Verify the pinned checkout, the reused CLI source and the dispatch contract
    // on every build; a silent drift here would change module-visible behaviour.
    outputs.upToDateWhen { false }
    workingDir(rootProject.projectDir)
    doFirst {
        val sdk = runtimeSdk.get().asFile
        val python = providers.gradleProperty("maplesuPython")
            .orElse(providers.environmentVariable("MAPLESU_PYTHON")).getOrElse("python")
        val arguments = mutableListOf(python, rootProject.file("tools/build_module_tools.py").absolutePath,
            "--ndk", sdk.resolve("ndk/26.1.10909125").absolutePath,
            "--output-dir", moduleToolsBuild.get().dir("native").asFile.absolutePath)
        providers.gradleProperty("maplesuRustToolchain").orNull?.let { arguments += listOf("--host-toolchain", it) }
        providers.gradleProperty("maplesuMingwBin").orNull?.let { arguments += listOf("--mingw", it) }
        commandLine(arguments)
    }
}
abstract class PackageMapleSuRuntime : DefaultTask() {
    @get:InputFile abstract val runtimeExecutable: RegularFileProperty
    @get:InputFile abstract val suExecutable: RegularFileProperty
    @get:InputFile abstract val bootstrapLibrary: RegularFileProperty
    // Hidden mode: the loader that is placed next to a host app's own native libraries
    // and named by the DT_NEEDED entry the backend adds. Same bytes the injection
    // receipt records, so AGP must not strip them.
    @get:InputFile abstract val parasiteLoaderLibrary: RegularFileProperty
    @get:InputFile abstract val patchExecutable: RegularFileProperty
    @get:InputFile abstract val policyExecutable: RegularFileProperty
    @get:Optional @get:InputFile abstract val policyBootLibrary: RegularFileProperty
    @get:Input abstract val policyBootSha256: Property<String>
    @get:Input abstract val policyBootBytes: Property<Long>
    @get:InputFile abstract val moduleToolsExecutable: RegularFileProperty
    // Ultra bridge: a SHARED library that links the fixed prebuilt SDK archive. Its own
    // target in runtime/CMakeLists.txt keeps it away from every Lite target.
    @get:InputFile abstract val ultraBridgeLibrary: RegularFileProperty
    // Fixed upstream BusyBox input, pinned by SHA-256 in
    // third_party/busybox/PROVENANCE.md. It is not a MapleSU source build.
    @get:InputFile abstract val busyboxExecutable: RegularFileProperty
    @get:OutputDirectory abstract val jniDirectory: DirectoryProperty

    @TaskAction fun packageExecutable() {
        val directory = jniDirectory.get().dir("arm64-v8a").asFile
        // Clear previous outputs first: a stale entry from an earlier build would be
        // packaged alongside the current set and become a second, unintended artifact
        // on the device (for example an old standalone resetprop next to the new
        // multicall host, which is exactly the duplicate-tool case to avoid).
        if (directory.isDirectory) directory.listFiles()?.forEach { it.delete() }
        val target = jniDirectory.get().file("arm64-v8a/libmaplesu_runtime.so").asFile
        target.parentFile.mkdirs()
        runtimeExecutable.get().asFile.copyTo(target, overwrite = true)
        suExecutable.get().asFile.copyTo(jniDirectory.get().file("arm64-v8a/libmaplesu_su.so").asFile, overwrite = true)
        bootstrapLibrary.get().asFile.copyTo(jniDirectory.get().file("arm64-v8a/libmaplesu_bootstrap.so").asFile, overwrite = true)
        parasiteLoaderLibrary.get().asFile.copyTo(
            jniDirectory.get().file("arm64-v8a/libmaplesu_parasite.so").asFile, overwrite = true)
        patchExecutable.get().asFile.copyTo(jniDirectory.get().file("arm64-v8a/libmaplesu_patch.so").asFile,
            overwrite = true)
        policyExecutable.get().asFile.copyTo(jniDirectory.get().file("arm64-v8a/libmaplesu_policy.so").asFile,
            overwrite = true)
        if (policyBootLibrary.isPresent) {
            val bytes = policyBootLibrary.get().asFile.readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            check(bytes.size.toLong() == policyBootBytes.get() && digest == policyBootSha256.get()) {
                "Boot backend bytes differ from the helper-bound release identity"
            }
            jniDirectory.get().file("arm64-v8a/libmaplesu_policy_boot.so").asFile.writeBytes(bytes)
        }
        // One host entry; module tool names are symlinks created at deployment.
        moduleToolsExecutable.get().asFile.copyTo(jniDirectory.get().file("arm64-v8a/libmaplesu_tools.so").asFile,
            overwrite = true)
        busyboxExecutable.get().asFile.copyTo(jniDirectory.get().file("arm64-v8a/libmaplesu_busybox.so").asFile,
            overwrite = true)
        // Published in the same atomic pass: the directory was cleared above, so a second
        // parallel copy task would be wiped by this one.
        ultraBridgeLibrary.get().asFile.copyTo(jniDirectory.get().file("arm64-v8a/libmaplesu_ultra_bridge.so").asFile,
            overwrite = true)
    }
}
val packageMapleSuRuntime = tasks.register<PackageMapleSuRuntime>("packageMapleSuRuntime") {
    dependsOn(buildMapleSuRuntime, buildMapleSuPatch, buildModulePolicy, buildModuleTools)
    runtimeExecutable.set(runtimeBuild.map { it.file("maplesu-runtime") })
    suExecutable.set(runtimeBuild.map { it.file("maplesu-su") })
    bootstrapLibrary.set(runtimeBuild.map { it.file("libmaplesu_bootstrap.so") })
    parasiteLoaderLibrary.set(runtimeBuild.map { it.file("libmaplesu_parasite.so") })
    patchExecutable.set(patchBuild.map { it.file("maplesu-kernel-patch") })
    policyExecutable.set(policyBuild.file("maplesu-policy"))
    policyBootBackend?.ultraSdkText("path")?.let { policyBootLibrary.set(rootProject.file(it)) }
    policyBootSha256.set(policyBootExpectedSha256)
    policyBootBytes.set(policyBootExpectedBytes)
    moduleToolsExecutable.set(moduleToolsBuild.map { it.file("native/maplesu-tools") })
    ultraBridgeLibrary.set(runtimeBuild.map { it.file("libmaplesu_ultra_bridge.so") })
    busyboxExecutable.set(rootProject.layout.projectDirectory.file("third_party/busybox/aarch64/busybox"))
    jniDirectory.set(runtimeJni)
}
androidComponents.onVariants { variant ->
    if (providers.gradleProperty("maplesuSkipNativePackaging").orNull != "true") {
        variant.sources.jniLibs?.addGeneratedSourceDirectory(packageMapleSuRuntime, PackageMapleSuRuntime::jniDirectory)
    }
}

// --- Jailbreak provider edge -------------------------------------------------
// Builds the retained-source provider entries offline and packages them with the
// digest manifest the App verifies before it starts an attempt. No provider is
// started here and no device is touched.
val jailbreakProviders = rootProject.layout.projectDirectory.dir("jailbreak-providers")
val jailbreakBuild = layout.buildDirectory.dir("jailbreakProviders")
val jailbreakJni = layout.buildDirectory.dir("generated/jailbreakJni")
val jailbreakAssets = layout.buildDirectory.dir("generated/jailbreakAssets")
val jailbreakManifest = layout.buildDirectory.file("generated/jailbreakManifest/maplesu-jailbreak-artifacts.json")
val jailbreakKoVariant = providers.gradleProperty("maplesuKoVariant").getOrElse("minimal")
// Step-2 canary packaging (default off). When on, the product in-kernel slot carries
// a non-ELF stub, the canary slot carries a freshly built maplesu_root.ko, and the
// canary helper is packaged as an asset the App stages 0700. A default build is
// byte-for-byte the product one.
val jailbreakRootCanary = providers.gradleProperty("maplesuRootCanary")
    .map { it.toBoolean() }.getOrElse(false)
val providerNdk = providers.gradleProperty("maplesuUltraviderNdk")
    .orElse(providers.environmentVariable("MAPLESU_PROVIDER_NDK"))
    .orElse(providers.provider { runtimeSdk.get().asFile.resolve("ndk/28.2.13676358").absolutePath })
/**
 * Build gate for jailbreak-providers/contracts/detection-baseline.json: the
 * restoration/cleanup shapes must exist in the code that would actually be
 * compiled. Dropping them fails the build instead of shipping a provider that
 * leaves the device dirty.
 */
val checkProviderTracelessInvariants = tasks.register<Exec>("checkProviderTracelessInvariants") {
    group = "jailbreak"
    description = "Verifies the provider traceless invariants (snapshot, verified restore, disarm handshake)"
    inputs.files(fileTree(jailbreakProviders) {
        include("**/*.py", "**/*.c", "**/*.cpp")
        exclude("**/__pycache__/**")
    })
    outputs.upToDateWhen { false }
    workingDir(rootProject.projectDir)
    doFirst {
        val python = providers.gradleProperty("maplesuPython")
            .orElse(providers.environmentVariable("MAPLESU_PYTHON")).getOrElse("python")
        commandLine(python,
            jailbreakProviders.file("check_traceless_invariants.py").asFile.absolutePath)
    }
}

val buildJailbreakProviders = tasks.register<Exec>("buildJailbreakProviders") {
    group = "jailbreak"
    description = "Builds the pinned Ghostlock/DirtyFrag provider entries and the shared lease consumer"
    dependsOn(checkProviderTracelessInvariants)
    inputs.files(fileTree(jailbreakProviders) {
        include("**/*.py", "**/*.c", "**/*.cpp", "**/*.h", "**/*.hpp", "**/*.cmake", "**/*.json",
            "**/*.S", "**/*.sh", "**/*.in", "**/*.rs", "**/*.toml", "**/Cargo.lock", "**/*.proto",
            "**/CMakeLists.txt")
        exclude("**/__pycache__/**")
    })
    inputs.files(policyHookBundles, policyHookBundles.resolveSibling("${policyHookBundles.nameWithoutExtension}.json"))
    outputs.dir(jailbreakBuild)
    // Retained upstream bytes are re-verified on every build, so this task must
    // run even when its own inputs look unchanged.
    outputs.upToDateWhen { false }
    workingDir(rootProject.projectDir)
    doFirst {
        val python = providers.gradleProperty("maplesuPython")
            .orElse(providers.environmentVariable("MAPLESU_PYTHON")).getOrElse("python")
        val arguments = mutableListOf(
            python, jailbreakProviders.file("build.py").asFile.absolutePath,
            "--dirtyfrag-adapter", "--ghostlock-adapter",
            "--ko-variant", jailbreakKoVariant)
        if (jailbreakRootCanary) arguments += "--root-canary"
        environment("MAPLESU_POLICY_HOOK_BUNDLES", policyHookBundles.absolutePath)
        environment("MAPLESU_MINGW_BIN", providers.gradleProperty("maplesuMingwBin")
            .orElse(providers.environmentVariable("MAPLESU_MINGW_BIN")).getOrElse(""))
        environment("MAPLESU_RUST_TOOLCHAIN", providers.gradleProperty("maplesuRustToolchain")
            .orElse(providers.environmentVariable("MAPLESU_RUST_TOOLCHAIN"))
            .getOrElse(if (System.getProperty("os.name").startsWith("Windows")) "stable-x86_64-pc-windows-gnu" else "stable"))
        arguments += listOf("--sdk", runtimeSdk.get().asFile.absolutePath, "--ndk-dir", providerNdk.get(),
            "--output", jailbreakBuild.get().asFile.absolutePath)
        commandLine(arguments)
    }
}
abstract class PackageJailbreakProviders : DefaultTask() {
    @get:InputFile abstract val buildReport: RegularFileProperty
    @get:InputDirectory abstract val providerDirectory: DirectoryProperty
    @get:Input abstract val koVariant: Property<String>
    @get:Input abstract val pythonExecutable: Property<String>
    @get:OutputDirectory abstract val jniDirectory: DirectoryProperty
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction fun packageEntries() {
        jniDirectory.get().asFile.mkdirs()
        assetsDirectory.get().asFile.mkdirs()
        val manifest = assetsDirectory.get().file("maplesu-jailbreak-artifacts.json").asFile
        val result = execOperations.exec {
            workingDir(project.rootProject.projectDir)
            commandLine(pythonExecutable.get(),
                providerDirectory.get().file("package.py").asFile.absolutePath,
                "--build", buildReport.get().asFile.parentFile.absolutePath,
                "--jni", jniDirectory.get().asFile.absolutePath,
                "--manifest", manifest.absolutePath)
        }
        check(result.exitValue == 0) { "provider packaging failed" }
    }
}
val packageJailbreakProviders = tasks.register<PackageJailbreakProviders>("packageJailbreakProviders") {
    dependsOn(buildJailbreakProviders)
    buildReport.set(jailbreakBuild.map { it.file("build.json") })
    providerDirectory.set(jailbreakProviders)
    koVariant.set(jailbreakKoVariant)
    pythonExecutable.set(providers.gradleProperty("maplesuPython")
        .orElse(providers.environmentVariable("MAPLESU_PYTHON")).getOrElse("python"))
    jniDirectory.set(jailbreakJni)
    assetsDirectory.set(jailbreakAssets)
}
abstract class PackagePolicyHooks : DefaultTask() {
    @get:InputFile abstract val bundles: RegularFileProperty
    @get:InputDirectory abstract val sourceDirectory: DirectoryProperty
    @get:Input abstract val pythonExecutable: Property<String>
    @get:Input abstract val ndkDirectory: Property<String>
    @get:OutputDirectory abstract val buildDirectory: DirectoryProperty
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations
    @TaskAction fun packageEntries() {
        val arguments = mutableListOf(pythonExecutable.get(),
            project.rootProject.file("tools/build_policy_hook_package.py").absolutePath,
            "--bundles", bundles.get().asFile.absolutePath,
            "--output", buildDirectory.get().asFile.absolutePath,
            "--assets", assetsDirectory.get().asFile.absolutePath,
            "--ndk", ndkDirectory.get())
        listOf("family-6.1.json", "6.6.89-android15-8-g7e1f3c083cc6-abogki467167594-4k.json", "family-6.12.json")
            .forEach { arguments += listOf("--contract", sourceDirectory.file("contracts/$it").get().asFile.absolutePath) }
        val result = execOperations.exec { workingDir(project.rootProject.projectDir); commandLine(arguments) }
        check(result.exitValue == 0) { "temporary policy backend packaging failed" }
    }
}
val packagePolicyHooks = tasks.register<PackagePolicyHooks>("packagePolicyHooks") {
    bundles.set(policyHookBundles.resolveSibling("${policyHookBundles.nameWithoutExtension}.json"))
    sourceDirectory.set(jailbreakProviders.dir("root-loader"))
    inputs.files(rootProject.file("tools/build_policy_hook_package.py"))
    pythonExecutable.set(providers.gradleProperty("maplesuPython")
        .orElse(providers.environmentVariable("MAPLESU_PYTHON")).getOrElse("python"))
    ndkDirectory.set(providerNdk)
    buildDirectory.set(layout.buildDirectory.dir("policyHookBackend"))
    assetsDirectory.set(layout.buildDirectory.dir("generated/policyHookAssets"))
    outputs.upToDateWhen { false }
}
// Select a fresh claim cohort without retaining duplicate static payload assets.
abstract class PackageUltraManagerClaim : DefaultTask() {
    @get:InputDirectory abstract val claimDirectory: DirectoryProperty
    @get:InputDirectory abstract val mainAssetsDirectory: DirectoryProperty
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty

    private fun readClaimJson(file: File): Map<String, Any?>? = try {
        if (!file.isFile) null else {
            @Suppress("UNCHECKED_CAST")
            JsonSlurper().parse(file) as? Map<String, Any?>
        }
    } catch (_: Throwable) {
        null
    }

    private fun Map<String, Any?>.claimObject(key: String): Map<String, Any?>? =
        (this[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    @TaskAction fun packageEntries() {
        val claim = claimDirectory.get().asFile
        val manifest = readClaimJson(claim.resolve("manifest.json"))
            ?: error("ULTRA_CLAIM_MANIFEST_REQUIRED")
        val payloads = manifest.claimObject("payloads") ?: error("ULTRA_CLAIM_PAYLOADS_REQUIRED")
        val families = setOf("android12-5.10", "android13-5.15", "android14-6.1",
            "android15-6.6", "android16-6.12", "android17-6.18")
        check(payloads.keys.containsAll(families) && payloads.keys.all { it.matches(Regex("android[0-9]+-[0-9]+\\.[0-9]+")) }) {
            "ULTRA_CLAIM_FAMILY_CLOSURE"
        }
        val support = manifest.claimObject("support") ?: error("ULTRA_CLAIM_SUPPORT_REQUIRED")
        check(support.keys == setOf("bootstrap", "materializer", "permissions")) {
            "ULTRA_CLAIM_SUPPORT_CLOSURE"
        }
        val expectedFiles = mutableSetOf("manifest.json")
        check((manifest["schemaVersion"] as? Number)?.toInt() in setOf(1, 2)) {
            "ULTRA_CLAIM_SCHEMA"
        }
        fun verify(relative: String, row: Any?) {
            check(relative.matches(Regex("[A-Za-z0-9._/-]+")) &&
                relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
                expectedFiles.add(relative)) { "ULTRA_CLAIM_FILE_PATH:$relative" }
            val identity = (row as? Map<*, *>) ?: error("ULTRA_CLAIM_IDENTITY:$relative")
            val file = claim.resolve(relative)
            check(file.canonicalFile.toPath().startsWith(claim.canonicalFile.toPath())) {
                "ULTRA_CLAIM_FILE_PATH:$relative"
            }
            check(file.isFile && file.length() == (identity["bytes"] as? Number)?.toLong() &&
                digest(file) == identity["sha256"]) { "ULTRA_CLAIM_FILE_CHANGED:$relative" }
        }
        payloads.forEach { (family, row) ->
            verify("$family/claim.so", row)
            val descriptorRow = row as? Map<*, *> ?: error("ULTRA_CLAIM_METADATA:$family")
            if (descriptorRow.containsKey("support")) {
                val overrides = descriptorRow["support"] as? Map<*, *>
                    ?: error("ULTRA_CLAIM_SUPPORT_OVERRIDE:$family")
                check(overrides.keys == setOf("bootstrap")) { "ULTRA_CLAIM_SUPPORT_OVERRIDE:$family" }
                val bootstrap = overrides["bootstrap"] as? Map<*, *>
                    ?: error("ULTRA_CLAIM_BOOTSTRAP:$family")
                val allowed = setOf("bytes", "sha256", "entryOffset", "imports", "relocations",
                    "sourceInputsIdentity", "retirement", "bootEntryOffset", "deploymentBootEntryOffset",
                    "deploymentAnchorVersion", "deploymentBindingBytes", "deploymentConfigBytes",
                    "deploymentRecordBytes", "path", "nativeCfiPolicy", "nativeCommandIdentity")
                check(bootstrap.keys.all { it in allowed } &&
                    bootstrap["path"] == "$family/bootstrap.bin") { "ULTRA_CLAIM_BOOTSTRAP_PATH:$family" }
                if (bootstrap.containsKey("nativeCfiPolicy") || bootstrap.containsKey("nativeCommandIdentity")) {
                    val cfi = bootstrap["nativeCfiPolicy"] as? Map<*, *>
                        ?: error("ULTRA_CLAIM_BOOTSTRAP_CFI:$family")
                    val command = bootstrap["nativeCommandIdentity"] as? Map<*, *>
                        ?: error("ULTRA_CLAIM_BOOTSTRAP_COMMAND:$family")
                    check(cfi.keys == setOf("kcfiOptionPresent", "kcfiEnabled", "normalizeIntegers") &&
                        cfi.values.all { it is Boolean } && cfi["kcfiOptionPresent"] == true &&
                        cfi["kcfiEnabled"] == true && cfi["normalizeIntegers"] ==
                        (bootstrap["retirement"] as? Map<*, *>)?.get("normalizeIntegers")) {
                        "ULTRA_CLAIM_BOOTSTRAP_CFI:$family"
                    }
                    check(command.keys == setOf("path", "bytes", "sha256") &&
                        (command["path"] as? String)?.isNotBlank() == true &&
                        ((command["bytes"] as? Number)?.toLong() ?: 0L) > 0L &&
                        (command["sha256"] as? String)?.matches(Regex("[0-9a-f]{64}")) == true) {
                        "ULTRA_CLAIM_BOOTSTRAP_COMMAND:$family"
                    }
                }
                val bytes = (bootstrap["bytes"] as? Number)?.toLong() ?: 0L
                val entry = (bootstrap["entryOffset"] as? Number)?.toLong() ?: -1L
                check(bytes > 0L && entry == 0L && bootstrap["imports"] == emptyList<Any>() &&
                    (bootstrap["relocations"] as? Number)?.toInt() == 0) {
                    "ULTRA_CLAIM_BOOTSTRAP_ENTRY:$family"
                }
                verify("$family/bootstrap.bin", bootstrap)
            }
            if ((manifest["schemaVersion"] as? Number)?.toInt() == 2) {
                val descriptor = row as? Map<*, *> ?: error("ULTRA_CLAIM_METADATA:$family")
                check(descriptor["metadataEncoding"] == "gzip-chunks-v1") { "ULTRA_CLAIM_METADATA:$family" }
                val chunks = descriptor["chunks"] as? List<*> ?: error("ULTRA_CLAIM_CHUNKS:$family")
                check(chunks.size in 1..8) { "ULTRA_CLAIM_CHUNKS:$family" }
                val paths = mutableSetOf<String>()
                val gzipDigest = MessageDigest.getInstance("SHA-256")
                var gzipBytes = 0L
                chunks.forEach { value ->
                    val chunk = value as? Map<*, *> ?: error("ULTRA_CLAIM_CHUNK:$family")
                    val path = chunk["path"] as? String ?: error("ULTRA_CLAIM_CHUNK_PATH:$family")
                    check(path.startsWith("$family/") && path.matches(Regex("[A-Za-z0-9._/-]+")) &&
                        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } && paths.add(path)) {
                        "ULTRA_CLAIM_CHUNK_PATH:$family"
                    }
                    val bytes = (chunk["bytes"] as? Number)?.toLong() ?: 0L
                    check(bytes in 1L..524288L) { "ULTRA_CLAIM_CHUNK_SIZE:$path" }
                    verify(path, chunk)
                    gzipBytes += bytes
                    check(gzipBytes <= 4194304L) { "ULTRA_CLAIM_METADATA_SIZE:$family" }
                    claim.resolve(path).inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            gzipDigest.update(buffer, 0, count)
                        }
                    }
                }
                check(gzipBytes == (descriptor["gzipBytes"] as? Number)?.toLong() &&
                    gzipDigest.digest().joinToString("") { "%02x".format(it) } == descriptor["gzipSha256"]) {
                    "ULTRA_CLAIM_METADATA_CHANGED:$family"
                }
            }
        }
        support.forEach { (name, row) -> verify("$name.bin", row) }
        check(claim.walkTopDown().filter { it.isFile }.map {
            it.relativeTo(claim).invariantSeparatorsPath
        }.toSet() == expectedFiles) { "ULTRA_CLAIM_FILE_SET" }
        val inputs = manifest.claimObject("inputs") ?: error("ULTRA_CLAIM_SOURCES_REQUIRED")
        check(inputs.isNotEmpty()) { "ULTRA_CLAIM_SOURCES_EMPTY" }
        inputs.forEach { (path, sha) ->
            val source = project.rootProject.file(path.replace('\\', '/'))
            check(source.isFile && digest(source) == sha) { "ULTRA_CLAIM_SOURCE_CHANGED:$path" }
        }
        project.sync {
            from(mainAssetsDirectory) { exclude("ultra-manager-claim/**") }
            from(claimDirectory) { into("ultra-manager-claim") }
            into(assetsDirectory)
        }
    }
}
val ultraManagerClaimDirectory = providers.gradleProperty("maplesuUltraClaimDirectory").orNull
val packageUltraManagerClaim = tasks.register<PackageUltraManagerClaim>("packageUltraManagerClaim") {
    if (ultraManagerClaimDirectory != null) claimDirectory.set(rootProject.file(ultraManagerClaimDirectory))
    mainAssetsDirectory.set(layout.projectDirectory.dir("src/main/assets"))
    assetsDirectory.set(layout.buildDirectory.dir("generated/selectedMainAssets"))
}
if (ultraManagerClaimDirectory != null) {
    android.sourceSets["main"].assets.setSrcDirs(emptyList<String>())
}
abstract class PackageUltraInitBridge : DefaultTask() {
    @get:InputDirectory abstract val sourceDirectory: DirectoryProperty
    @get:Input abstract val targetArguments: MapProperty<String, String>
    @get:Input abstract val catalogPlanPath: Property<String>
    @get:InputFiles abstract val targetFiles: ConfigurableFileCollection
    @get:Input abstract val pythonExecutable: Property<String>
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty
    @get:Inject abstract val execOperations: ExecOperations

    private fun identity(file: File): Map<String, Any> {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return mapOf("path" to file.absolutePath, "bytes" to file.length(),
            "sha256" to digest.digest().joinToString("") { "%02x".format(it) })
    }

    @TaskAction fun packageEntries() {
        val required = listOf("target-boot", "kernel-image", "kernel-build", "target-config",
            "symvers", "bridge", "handoff", "handoff-verifier", "target-identity", "target-contract")
        val selected = targetArguments.get()
        val missing = required.filter { selected[it].isNullOrBlank() }.toMutableList()
        selected.filterKeys { it != "credential-symbol" }.forEach { (name, path) ->
            val file = project.rootProject.file(path)
            if (if (name == "kernel-build") !file.isDirectory else !file.isFile) missing += "$name:$path"
        }
        if (selected.containsKey("credential-file") != selected.containsKey("credential-symbol")) {
            missing += "credential-file/credential-symbol pair"
        }
        val assets = assetsDirectory.get().asFile
        val bundle = assets.resolve("ultra-init-bridge")
        val receipt = receiptFile.get().asFile
        receipt.parentFile.mkdirs()
        val result = linkedMapOf<String, Any>("schema" to "maplesu-init-bridge-package/1",
            "targetArguments" to selected, "missingInputs" to missing,
            "source" to identity(sourceDirectory.get().file("package.py").asFile),
            "status" to "MISSING_TARGET_INPUTS", "artifactQualified" to false)
        fun saveReceipt() { receipt.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(result)) + "\n") }
        result["inputs"] = targetFiles.files.filter { it.isFile }.sortedBy { it.absolutePath }.map(::identity)
        saveReceipt()
        check(missing.isEmpty()) { "ULTRA_BRIDGE_TARGET_INPUTS_REQUIRED: ${missing.joinToString()}; receipt=$receipt" }
        // package.py publishes its manifest last and requires a fresh output directory.
        project.delete(bundle)
        assets.mkdirs()
        val arguments = mutableListOf(pythonExecutable.get(),
            sourceDirectory.get().file("package.py").asFile.absolutePath)
        selected.forEach { (name, value) -> arguments += listOf("--$name",
            if (name == "credential-symbol") value else project.rootProject.file(value).absolutePath) }
        arguments += listOf("--output", bundle.absolutePath)
        result["command"] = arguments
        result["status"] = "RUNNING"
        saveReceipt()
        val execution = execOperations.exec {
            workingDir(project.rootProject.projectDir)
            commandLine(arguments)
            isIgnoreExitValue = true
        }
        result["exitCode"] = execution.exitValue
        if (execution.exitValue == 0 && catalogPlanPath.get().isNotEmpty()) {
            val catalogArguments = listOf(pythonExecutable.get(),
                sourceDirectory.get().file("package_catalog.py").asFile.absolutePath,
                "--plan", project.rootProject.file(catalogPlanPath.get()).absolutePath,
                "--output", bundle.absolutePath, "--append-to-default")
            result["catalogCommand"] = catalogArguments
            result["status"] = "CATALOG_RUNNING"
            saveReceipt()
            val catalogExecution = execOperations.exec {
                workingDir(project.rootProject.projectDir)
                commandLine(catalogArguments)
                isIgnoreExitValue = true
            }
            result["catalogExitCode"] = catalogExecution.exitValue
            if (catalogExecution.exitValue != 0) {
                result["status"] = "CATALOG_FAILED"
                saveReceipt()
                error("Ultra init bridge catalog packaging failed; receipt=$receipt")
            }
        }
        result["status"] = if (execution.exitValue == 0) "PACKAGED" else "PACKAGE_FAILED"
        if (execution.exitValue == 0) {
            check(bundle.resolve("manifest.json").isFile) { "ULTRA_BRIDGE_MANIFEST_MISSING" }
            result["files"] = bundle.walkTopDown().filter { it.isFile }.sortedBy { it.path }.map(::identity).toList()
        }
        saveReceipt()
        check(execution.exitValue == 0) { "Ultra init bridge packaging failed; receipt=$receipt" }
    }
}
val ultraInitBridgeArguments = listOf("target-boot", "kernel-image", "kernel-build", "target-config", "symvers",
    "bridge", "handoff", "handoff-verifier", "target-identity", "target-contract",
    "btf", "session-abi", "task-profile-abi", "credential-file", "credential-symbol").mapNotNull { name ->
    providers.gradleProperty("maplesuInitBridge." + name).orNull?.let { name to it }
}.toMap()
val packageUltraInitBridge = tasks.register<PackageUltraInitBridge>("packageUltraInitBridge") {
    sourceDirectory.set(rootProject.layout.projectDirectory.dir("runtime/ultra/boot/init-bridge"))
    targetArguments.set(ultraInitBridgeArguments)
    catalogPlanPath.set(providers.gradleProperty("maplesuInitBridge.catalog-plan").orElse(""))
    targetFiles.from(catalogPlanPath.map { value ->
        if (value.isEmpty()) emptyList<File>() else {
            val plan = rootProject.file(value)
            check(plan.isFile) { "ULTRA_CATALOG_PLAN_MISSING:$plan" }
            val declared = JsonSlurper().parse(plan) as? Map<*, *> ?: error("ULTRA_CATALOG_PLAN_SCHEMA")
            val rows = declared["variants"] as? List<*> ?: error("ULTRA_CATALOG_PLAN_VARIANTS")
            val inputs = mutableListOf(plan)
            fun planFile(value: String): File = File(value).let { if (it.isAbsolute) it else File(plan.parentFile, value) }
            rows.forEach { value ->
                val row = value as? Map<*, *> ?: error("ULTRA_CATALOG_PLAN_VARIANT")
                val bundle = planFile(row["bundle"] as? String ?: error("ULTRA_CATALOG_PLAN_BUNDLE"))
                val selection = planFile(row["sourceSelection"] as? String ?: error("ULTRA_CATALOG_PLAN_SELECTION"))
                val build = planFile(row["strictBuild"] as? String ?: error("ULTRA_CATALOG_PLAN_BUILD"))
                inputs += listOf(selection, build)
                inputs += bundle.walkTopDown().filter { it.isFile }.toList()
                check(selection.isFile) { "ULTRA_CATALOG_SOURCE_SELECTION_MISSING:$selection" }
                val sources = JsonSlurper().parse(selection) as? Map<*, *> ?: error("ULTRA_CATALOG_SOURCE_SELECTION")
                (sources["inputs"] as? List<*> ?: error("ULTRA_CATALOG_SOURCE_INPUTS")).forEach { item ->
                    val selected = (item as? Map<*, *>)?.get("selected") as? Map<*, *> ?: error("ULTRA_CATALOG_SOURCE_SELECTED")
                    inputs += File(selected["path"] as? String ?: error("ULTRA_CATALOG_SOURCE_PATH"))
                }
                val manifest = JsonSlurper().parse(bundle.resolve("manifest.json")) as? Map<*, *> ?: error("ULTRA_CATALOG_MANIFEST")
                val provenance = JsonSlurper().parse(bundle.resolve(manifest["bundleSources"] as? String
                    ?: error("ULTRA_CATALOG_BUNDLE_SOURCES"))) as? Map<*, *> ?: error("ULTRA_CATALOG_BUNDLE_SOURCES")
                val original = ((provenance["inputs"] as? Map<*, *>)?.get("bridge") as? Map<*, *>)?.get("path") as? String
                    ?: error("ULTRA_CATALOG_ORIGINAL_MODULE")
                inputs += File(original)
            }
            inputs.distinctBy { it.absolutePath }
        }
    })
    targetFiles.from(targetArguments.map { selected -> selected.filterKeys { it != "credential-symbol" }
        .flatMap { (name, path) ->
            val file = rootProject.file(path)
            if (name == "kernel-build") listOf(file.resolve(".config"), file.resolve("Module.symvers"),
                file.resolve("include/generated/utsrelease.h")) else listOf(file)
        } })
    pythonExecutable.set(providers.gradleProperty("maplesuPython")
        .orElse(providers.environmentVariable("MAPLESU_PYTHON")).getOrElse("python"))
    assetsDirectory.set(layout.buildDirectory.dir("generated/ultraInitBridgeAssets"))
    receiptFile.set(layout.buildDirectory.file("reports/ultraInitBridge/package-receipt.json"))
}
androidComponents.onVariants { variant ->
    if (ultraManagerClaimDirectory != null) {
        variant.sources.assets?.addGeneratedSourceDirectory(packageUltraManagerClaim,
            PackageUltraManagerClaim::assetsDirectory)
    }
    // Normal uses its embedded KMI modules; Ultra boot uses the image patcher.
    // Only the separate Ultra init_boot carrier consumes this target-specific bundle.
    // Any supplied bridge input still selects the complete, strict packaging task.
    if (ultraInitBridgeArguments.isNotEmpty()) {
        variant.sources.assets?.addGeneratedSourceDirectory(packageUltraInitBridge, PackageUltraInitBridge::assetsDirectory)
    }
    variant.sources.assets?.addGeneratedSourceDirectory(packagePolicyHooks, PackagePolicyHooks::assetsDirectory)
    if (providers.gradleProperty("maplesuSkipNativePackaging").orNull != "true") {
        variant.sources.jniLibs?.addGeneratedSourceDirectory(packageJailbreakProviders,
            PackageJailbreakProviders::jniDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(packageJailbreakProviders,
            PackageJailbreakProviders::assetsDirectory)
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation(project(":image-core"))
    implementation(project(":installer-core"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // Android UI tests use 1.2.0; both tested variants must resolve the same runtime.
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigationevent.compose)
    implementation(libs.com.github.topjohnwu.libsu.core)
    implementation(project(":libsu-service"))
    implementation(libs.com.github.topjohnwu.libsu.io)
    implementation(libs.dev.rikka.rikkax.parcelablelist)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.task.list.items)
    implementation(libs.androidx.webkit)
    implementation(libs.hiddenapibypass)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.material.kolor)
    implementation(libs.appiconloader)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.protobuf.kotlin.lite)
    testImplementation(libs.junit)
    // JVM receipt tests need real JSON behavior instead of Android's mockable stubs.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(project(":installer-core"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

// Identity is generated from the same release manifest consumed by BuildConfig.
val verifyUltraSdkIdentity by tasks.registering(Exec::class) {
    onlyIf { ultraSdkDirectory.resolve("SDK-RELEASE.json").isFile }
    workingDir(rootProject.projectDir)
    doFirst {
        val python = providers.gradleProperty("maplesuPython")
            .orElse(providers.environmentVariable("MAPLESU_PYTHON")).getOrElse("python")
        commandLine(python, rootProject.file("scripts/sync-ultra-sdk-identity.py"), "--check",
            "--manifest", ultraSdkDirectory.resolve("SDK-RELEASE.json"),
            "--output", ultraSdkDirectory.resolve("SDK-IDENTITY.cmake"))
    }
}
tasks.named("preBuild") { dependsOn(verifyUltraSdkIdentity) }
