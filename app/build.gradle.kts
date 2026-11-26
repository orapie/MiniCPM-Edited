import java.util.Properties
import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
}

base {
    archivesName.set(rootProject.name)
}

abstract class PrepareDatabaseBundleTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val preparationScript: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val supportingScripts: ConfigurableFileCollection

    @get:Input
    abstract val pythonExecutable: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        execOperations.exec {
            commandLine(pythonExecutable.get(), preparationScript.get().asFile.absolutePath,
                "--source", sourceDirectory.get().asFile.absolutePath,
                "--output-assets", outputDirectory.get().asFile.absolutePath)
        }
    }
}

val prepareDatabaseBundle = tasks.register<PrepareDatabaseBundleTask>("prepareDatabaseBundle") {
    group = "database"
    description = "Validate the root database bundle and stage only its controlled APK assets"
    sourceDirectory.set(rootProject.layout.projectDirectory.dir("database"))
    preparationScript.set(rootProject.layout.projectDirectory.file("scripts/prepare_database_bundle.py"))
    supportingScripts.from(rootProject.files(
        "scripts/prepare_database_v22.py", "scripts/prepare_v22_migration.py",
        "scripts/audit_semantic_database.py", "scripts/v22_evidence_projection.py",
    ))
    pythonExecutable.set(providers.gradleProperty("databasePython").orElse("python3"))
    outputDirectory.set(layout.buildDirectory.dir("generated/databaseAssets"))
}

abstract class PrepareProgramAssetsTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val promptTemplates: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        fileSystemOperations.sync {
            into(outputDirectory)
            from(promptTemplates) { into("prompts") }
        }
    }
}

val prepareProgramAssets = tasks.register<PrepareProgramAssetsTask>("prepareProgramAssets") {
    promptTemplates.set(layout.projectDirectory.dir("src/main/assets/prompts"))
    outputDirectory.set(layout.buildDirectory.dir("generated/programAssets"))
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val llamaSourceDir = localProperties.getProperty("llama.src.dir")
    ?.takeIf(String::isNotBlank)

android {
    namespace = "com.example.minicpm_v_demo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.example.minicpm_v_demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 17
        versionName = "2.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Both JNI DSOs are application runtime dependencies: the
                // foreground engine and the isolated text-only Agent-A engine.
                targets += listOf("minicpm_v_demo", "minicpm_attribution")
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_BUILD_TOOLS=OFF"
                arguments += "-DLLAMA_OPENSSL=OFF"

                // Android is a cross-compile target; GGML_NATIVE invokes try_run()
                // and would incorrectly probe the macOS host CPU.
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_LLAMAFILE=ON"
                arguments += "-DGGML_CPU_KLEIDIAI=ON"
                arguments += "-DLLAMA_CURL=OFF"
                llamaSourceDir?.let {
                    arguments += "-DLLAMA_SRC=${file(it).absolutePath.replace('\\', '/')}"
                }
            }
        }
    }

    // Release signing config. Credentials live in ~/.gradle/gradle.properties
    // (outside any git repo, only readable by your local mac account).
    // If those properties aren't set the release build still works but produces
    // an unsigned apk - useful e.g. on CI without secrets.
    signingConfigs {
        create("release") {
            val keystorePath = providers.gradleProperty("MINICPMV_KEYSTORE").orNull
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = providers.gradleProperty("MINICPMV_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("MINICPMV_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("MINICPMV_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            // Keep ProGuard/R8 disabled: the app calls native JNI symbols and
            // shrinking the Kotlin side has no measurable benefit here, while
            // an over-aggressive shrinker is the most common cause of crashes
            // in apps with lots of JNI bindings.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the signing config when the keystore actually exists,
            // so contributors without the secret can still run :assembleRelease
            // (it'll produce an unsigned apk in that case).
            val signingCfg = signingConfigs.getByName("release")
            if (signingCfg.storeFile?.exists() == true) {
                signingConfig = signingCfg
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    buildFeatures {
        viewBinding = true
    }

    sourceSets.getByName("main").assets.directories.clear()
    // CMake supplies the JNI libraries. Ignore stale copies from older builds.
    sourceSets.getByName("main").jniLibs.directories.clear()
    sourceSets.getByName("test").kotlin.directories.add("src/sharedTest/java")
    sourceSets.getByName("androidTest").kotlin.directories.add("src/sharedTest/java")

    androidResources {
        noCompress.add("gguf")
        noCompress.add("bin")
        // The Phase 1 character cognition graph is copied from assets into
        // filesDir before Android SQLite opens it read-only.
        noCompress.add("sqlite3")
        noCompress.add("onnx")
    }
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareDatabaseBundle, PrepareDatabaseBundleTask::outputDirectory)
    variant.sources.assets?.addGeneratedSourceDirectory(prepareProgramAssets, PrepareProgramAssetsTask::outputDirectory)
}

tasks.register<Exec>("verifyDebugDatabaseAssets") {
    group = "verification"
    description = "Verify the final Debug APK contains only the current database and approved program assets"
    dependsOn("assembleDebug")
    commandLine(providers.gradleProperty("databasePython").orElse("python3").get(),
        rootProject.file("scripts/verify_database_apk.py").absolutePath,
        "--apk", layout.buildDirectory.file("outputs/apk/debug/${rootProject.name}-debug.apk").get().asFile.absolutePath,
        "--source", rootProject.file("database").absolutePath,
        "--program-source", file("src/main/assets").absolutePath,
        "--report", layout.buildDirectory.file("reports/database/apk_inventory.json").get().asFile.absolutePath)
}

dependencies {
    // 1.27.0 ships Android native libraries with 16 KB ELF page alignment.
    // Keep Android and JVM artifacts on precisely the same ORT version.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.27.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Markdown rendering for AI streaming responses (headings, bold, lists, code, etc.)
    implementation("io.noties.markwon:core:4.6.2")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
