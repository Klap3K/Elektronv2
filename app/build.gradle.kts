import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// github does not use Firebase or Google Services; gms requires local configuration.
val variantTasks = gradle.startParameter.taskNames.filter { name ->
    !name.startsWith("-") && listOf("github", "gms").any { name.contains(it, ignoreCase = true) }
}
val needsGoogleServices = providers.gradleProperty("elektron.gms").orNull?.toBooleanStrictOrNull()
    ?: (variantTasks.isEmpty() || variantTasks.any { it.contains("gms", ignoreCase = true) })
if (needsGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
    // Mixed-variant invocations must never require Google configuration for github.
    tasks.matching { it.name.contains("Github") && it.name.endsWith("GoogleServices") }.configureEach { enabled = false }
}

// Podpis release: dane klucza w keystore.properties (w .gitignore, NIGDY w repozytorium).
// Brak pliku => assembleRelease buduje niepodpisany APK (nie wysypuje się na świeżym klonie).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseStoreFile = providers.environmentVariable("RELEASE_STORE_FILE").orNull
val hasReleaseSigning = releaseStoreFile != null || keystorePropsFile.exists()

android {
    namespace = "pl.zse.bydgoszcz.elektron"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.zse.bydgoszcz.elektron.vulcan"
        minSdk = 26
        targetSdk = 35
        versionCode = 47
        versionName = "2.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // github: GitHub Releases bez Firebase, z instalatorem APK.
    // gms: GitHub z opcjonalnymi powiadomieniami Firebase.
    flavorDimensions += "distribution"
    productFlavors {
        create("gms") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATE_CHECK", "true")
        }
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATE_CHECK", "true")
        }
    }

    // Do not embed Google's encrypted dependency metadata in distributed APKs.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = file(releaseStoreFile)
                storePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").get()
            } else if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets.getByName("test").resources.srcDir("schemas")

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric: zasoby Androida dostępne w testach JVM.
            isIncludeAndroidResources = true
        }
    }

    lint {
        // Lint production code; Robolectric/Compose tests are compiled and executed separately.
        // Avoid FIR/UAST scanning the large JVM test environment on Windows.
        ignoreTestSources = true
    }
}

kotlin {
    // Cel JVM wprost zamiast jvmToolchain(17): build działa z każdym JDK 17+ (CI używa JDK 21),
    // a kod nadal jest kompilowany pod Javę 17 - tak jak compileOptions wyżej.
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.jsoup)
    implementation(libs.coil.compose)
    implementation(libs.osmdroid)
    implementation(libs.glance.appwidget)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    // Firebase tylko w opcjonalnym wariancie gms.
    "gmsImplementation"(platform(libs.firebase.bom))
    "gmsImplementation"(libs.firebase.messaging)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    // Testy repozytoriów na prawdziwej bazie Room w pamięci (SQLite przez Robolectric).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
}

// Robolectric rozpakowuje natywne biblioteki (SQLite, dane ICU) do katalogu tymczasowego.
// Na systemach z /tmp w pamięci RAM (tmpfs, np. CachyOS) kończyło się to awarią JVM
// (SIGSEGV w SQLiteConnectionNatives.nativeOpen, "Invalid ICU dat file path").
// Katalog tymczasowy testów leży więc w build/, na dysku.
tasks.withType<Test>().configureEach {
    val robolectricTmp = layout.buildDirectory.dir("tmp/robolectric").get().asFile
    doFirst { robolectricTmp.mkdirs() }
    systemProperty("java.io.tmpdir", robolectricTmp.absolutePath)
    // Pełny opis nieudanego testu w logu (CI) - raport HTML nie zawsze da się pobrać.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
