plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Часть инструментов Android не переносит не-ASCII в пути: protoc не находит файлы, а
// тест-воркер Gradle не может загрузить классы из classpath. Если каталог проекта содержит
// такие символы, уводим сборку в ASCII-каталог — свой для каждой копии проекта, иначе две
// копии дерутся за один и тот же build. Готовые APK в любом случае попадают в dist/.
val projectPath: String = rootDir.absolutePath
if (projectPath.any { it.code > 127 }) {
    val unique = Integer.toHexString(projectPath.hashCode())
    layout.buildDirectory.set(File(System.getProperty("java.io.tmpdir"), "tv-remote-build-$unique/app"))
}

android {
    namespace = "com.sprit.tvremote"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.sprit.tvremote"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Подписываем отладочным ключом, чтобы APK можно было поставить сразу.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

tasks.withType<Test>().configureEach {
    // Диагностика против живого телевизора: gradlew :app:testDebugUnitTest -PtvHost=192.168.1.106
    systemProperty("tv.host", providers.gradleProperty("tvHost").orNull ?: "")
    systemProperty("tv.voiceFile", providers.gradleProperty("tvVoice").orNull ?: "")
    systemProperty("tv.certDir", providers.gradleProperty("tvCert").orNull ?: "")
    systemProperty("tv.voiceFile16", providers.gradleProperty("tvVoice16").orNull ?: "")
    testLogging { showStandardStreams = true }
}

/** Собранные APK кладём в android/dist — каталог сборки лежит вне проекта. */
val exportApk = tasks.register<Copy>("exportApk") {
    from(layout.buildDirectory.dir("outputs/apk")) { include("**/*.apk") }
    into(rootProject.layout.projectDirectory.dir("dist"))
    eachFile { path = name }
    includeEmptyDirs = false
}

tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    finalizedBy(exportApk)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Набор иконок заморожен на 1.7.8 и в BOM больше не входит, поэтому версия задана явно.
    // Лишние иконки вырезает R8 при сборке release.
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Протокол Remote v2 говорит protobuf-сообщениями поверх TLS.
    implementation("com.google.protobuf:protobuf-javalite:4.35.1")

    // Нужен только для выпуска самоподписанного клиентского сертификата:
    // в публичном API Android построителя X.509 нет.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.82")
    implementation("org.bouncycastle:bcprov-jdk18on:1.82")

    testImplementation("junit:junit:4.13.2")
}
