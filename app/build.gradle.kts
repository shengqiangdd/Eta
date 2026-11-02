plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val releaseStoreFile = System.getenv("ETA_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("ETA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("ETA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ETA_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// CI 对非发版构建传入提交号，按 SemVer 构建元数据追加为 +<sha>，不影响版本先后；本地与发版构建不传
val buildMetadata = providers.gradleProperty("eta.buildMetadata").orNull?.takeIf { it.isNotBlank() }
require(buildMetadata == null || buildMetadata.matches(Regex("[0-9A-Za-z.-]+"))) {
    "eta.buildMetadata 只能包含字母、数字、点和连字符"
}
val buildMetadataSuffix = buildMetadata?.let { "+$it" }.orEmpty()

// versionCode 基数：yyyyMMdd + 两位当日序号（01 起），发版时随 versionName 一起手动递增。
val etaVersionCode = 2026103101

android {
    namespace = "io.github.mangi.eta"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        // canary 并行安装：-Peta.applicationIdSuffix=.canary 时生成 io.github.mangi.eta.canary，
        // 与正式包并存，验证新功能时不打断正在用的那个。传空或不传即正式包名。
        applicationId = "io.github.mangi.eta" + (providers.gradleProperty("eta.applicationIdSuffix").orNull ?: "")
        minSdk = 33
        targetSdk = 37
        // versionCode 规则：yyyyMMdd + 两位当日序号（01 起），发版时随 versionName 一起手动递增（见上方 etaVersionCode）。
        versionCode = etaVersionCode
        versionName = "3.4.0"

        ndk {
            // 同时过滤本地与依赖 AAR 的原生库，确保 APK 仅包含 ARM64。
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // 版本名带构建类型标记，设置页与 APK 文件名据此区分 Debug 包
            versionNameSuffix = "-debug" + buildMetadataSuffix
            isMinifyEnabled = false
            isPseudoLocalesEnabled = true
        }
        release {
            versionNameSuffix = buildMetadataSuffix
            // release 的 versionCode（etaVersionCode + 1）在下方 androidComponents 里设：
            // AGP 9 的 buildType 上没有 versionCode 属性（写成这里会 Unresolved reference）。
            // 没有 ETA_RELEASE_* 时回退到 debug 签名：本地要拿 release 包做真机验证
            // （R8 混淆后还能不能启动）就必须装得上去，否则只能打 unsigned APK 再手工签名。
            // CI/发版环境提供了 release 密钥，这里仍然走 release 签名，不改变发版行为。
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "b+zh+Hans", "b+zh+Hant")
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libproot_exec.so", "**/libproot_loader.so", "**/libeta_pty.so")
        }
        resources {
            // 合并 Xposed 模块声明，避免 release 裁剪后模块入口失效
            merges += "META-INF/xposed/*"
            // 仅排除会引发打包冲突的签名/版本元数据，避免误伤 Compose 资源
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // 存量告警记在基线里（418 条：410 warning + 7 hint，已逐类判读过，见 AGENT_RUNTIME.md）。
        // 这样 lintDebug 只对「新增」告警报错；否则存量永远红，门禁等于没有。
        baseline = file("lint-baseline.xml")
        // 关键：abortOnError 只管 error 级别。而绝大多数 lint 检查默认是 **warning**
        // （SdCardPath、UnusedResources、UseKtx 等），只配 baseline 的话新增告警只会
        // 打印一行“Lint found 1 warning”然后 BUILD SUCCESSFUL——2026-10-24 的探针实验
        // 实测如此。warningsAsErrors 把未被基线吸收的 warning 升为 error，门禁才真能拦。
        warningsAsErrors = true
        // versionCode 采用日期编码，已发布值只能递增；上限 2100000000 足够覆盖到 2099 年。
        disable += "HighAppVersionCode"
        // 2026-11-01：GradleDependency 会因「上游发了新版本」把门禁变红（本轮一次报 4 条：
        // kotlin 2.4.20→2.4.21、markdown 0.7.16→0.7.18、hiddenapibypass 6.1→6.2），
        // 与代码质量无关，且离线构建不可能自动升级。它还带 autofix：本轮 lint 打印过
        // 「This issue has been automatically fixed」，虽然 git diff 显示 toml 没被改，
        // 但把「自动改构建文件」留在门禁里不值得赌。
        // 升级依赖应当是一次显式决定（改 toml + 重跑门禁），不该由 lint 在每一轮捎带决定。
        disable += "GradleDependency"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric 在高版本 JDK 下需要访问内部 API，参数只作用于测试 JVM。
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED",
            )
        }
    }
}

ksp {
    // Room 导出的 schema 快照落到 app/schemas/：没有它，迁移只能在真机上“跑到才知错”。
    arg("room.schemaLocation", "$projectDir/schemas")
}

androidComponents {
    onVariants { variant ->
        // 文件名只取 versionName：构建类型标记与 CI 构建元数据都由 versionNameSuffix 带出
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { "Eta-v$it.apk" })
        }
    }
}

// release 的 versionCode 比 debug 高 1：本地常把 debug 包装到主包验证，验完换回 release 时
// 同号会被系统当降级拒装（只能 `pm install -d` 硬装，实测踩过这个坑）。
// AGP 9 的 buildType 上没有 versionCode 属性，只能在 variant 层设。
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(etaVersionCode + 1)
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    implementation(libs.jsoup)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    compileOnly(libs.libxposed.api)
    // UI 侧 RemotePreferences 写入桥：通过 XposedService 将配置提交到 LSPosed 数据库；
    // Hook 侧用 XposedInterface.getRemotePreferences 读取当前进程持有的配置缓存。
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    // 只使用 GFM 解析器；聊天渲染层由 ui/markdown 自建，按块冻结并接入逐字显现。
    implementation(libs.intellij.markdown)
    implementation(libs.hidden.api.bypass)

    // DataStore：Provider / Model 结构化 JSON 与当前选中 ID 等键值
    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp：替代 HttpURLConnection，支持 SSE
    implementation(libs.okhttp)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.okhttp.sse)

    // Kotlinx Serialization：Provider 设置与运行时配置 JSON
    implementation(libs.kotlinx.serialization.json)

    // Coroutines：显式引入，避免依赖传递版本不确定
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    // 全库内容搜索的 SQL 契约测试用（见 ConversationSearchSqlContractTest）：
    // Robolectric 的 SQLite native 只有 x86_64，本机 arm64 跑不了，改用 JDBC 直连
    // 执行 DAO 上的 @Query 原文。版本与 room-testing 的传递依赖一致，离线可解析。
    testImplementation(libs.sqlite.jdbc)
}
