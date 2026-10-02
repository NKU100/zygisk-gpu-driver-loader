import groovy.json.JsonBuilder
import com.android.build.api.artifact.SingleArtifact
import org.apache.commons.codec.binary.Hex
import org.apache.tools.ant.filters.FixCrLfFilter
import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.agp.app)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

abstract class ExtractDriverImportDex : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:OutputFile
    abstract val dexFile: RegularFileProperty

    @TaskAction
    fun extract() {
        val apkFiles = apkDirectory.get().asFile.listFiles { file ->
            file.isFile && file.extension.equals("apk", ignoreCase = true)
        } ?: throw GradleException("APK output directory is unavailable")
        if (apkFiles.size != 1) throw GradleException("Expected one APK, found ${apkFiles.size}")

        ZipFile(apkFiles.single()).use { apk ->
            val dexEntries = apk.entries().asSequence()
                .filter { it.name.matches(Regex("classes(\\d*)\\.dex")) }
                .toList()
            val helperDescriptor = "Lio/github/nku100/gpudriver/importer/DriverPathHelper;"
                .toByteArray(Charsets.UTF_8)
            val dex = dexEntries.singleOrNull { entry ->
                apk.getInputStream(entry).use { input -> input.readBytes().containsSequence(helperDescriptor) }
            } ?: throw GradleException("APK must contain exactly one DEX shard with DriverPathHelper")
            val output = dexFile.get().asFile
            output.parentFile.mkdirs()
            apk.getInputStream(dex).use { input ->
                output.outputStream().use(input::copyTo)
            }
        }
    }

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty() || sequence.size > size) return false
        for (start in 0..(size - sequence.size)) {
            var offset = 0
            while (offset < sequence.size && this[start + offset] == sequence[offset]) offset++
            if (offset == sequence.size) return true
        }
        return false
    }
}

// All git commands use Provider-based lazy evaluation (configuration-cache friendly).
// Values are only resolved when a task actually needs them, not during configuration.
val commitCount = providers.exec { commandLine("git", "rev-list", "HEAD", "--count") }
    .standardOutput.asText.map { it.trim().toInt() }
val commitHash = providers.exec { commandLine("git", "rev-parse", "--verify", "--short", "HEAD") }
    .standardOutput.asText.map { it.trim() }
val tag = providers.exec {
    commandLine("git", "tag", "--points-at", "HEAD", "--sort=-v:refname")
    isIgnoreExitValue = true
}.standardOutput.asText.map { text ->
    // Pick the first v* tag on HEAD; fall back to "ci" for untagged commits
    text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("v") }.orEmpty()
        .ifEmpty { "ci" }
}

val moduleRepoUrl = rootProject.extra["moduleRepo"] as String
val gitHubPattern = Regex("""https://github\.com/([^/]+)/([^/]+)$""")
val gitHubMatch = gitHubPattern.find(moduleRepoUrl)
val gitHubUser = providers.provider { gitHubMatch?.groupValues?.get(1) ?: "NKU100" }
val gitHubRepo = providers.provider { gitHubMatch?.groupValues?.get(2) ?: "zygisk-module-webui-template" }

// Resolve extra properties eagerly into plain vals (not delegated properties).
// `by rootProject.extra` delegates capture rootProject, which cannot be
// serialized/deserialized by the Configuration Cache.
val moduleId = rootProject.extra["moduleId"] as String
val moduleName = rootProject.extra["moduleName"] as String
val moduleAuthor = rootProject.extra["moduleAuthor"] as String
val moduleDesc = rootProject.extra["moduleDesc"] as String
val moduleLibName = rootProject.extra["moduleLibName"] as String
@Suppress("UNCHECKED_CAST")
val abiList = rootProject.extra["abiList"] as List<String>

android {
    defaultConfig {
        ndk {
            abiFilters.addAll(abiList)
        }
        externalNativeBuild {
            cmake {
                targets(moduleLibName)
                cppFlags("-std=c++20")
                arguments(
                    "-DANDROID_STL=c++_static", "-DMODULE_NAME=$moduleLibName",
                    "-DMODULE_ID=$moduleId",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_C_COMPILER_LAUNCHER=ccache",
                    "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }
}

androidComponents.onVariants { variant ->
    val variantLowered = variant.name.lowercase()
    val variantCapped = variant.name.replaceFirstChar { it.titlecase() }
    val buildTypeLowered = variant.buildType?.lowercase() ?: "debug"
    val supportedAbis = abiList.joinToString(" ") {
        when (it) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "arm"
            "x86" -> "x86"
            "x86_64" -> "x64"
            "riscv64" -> "riscv64"
            else -> error("unsupported abi $it")
        }
    }

    // Use .dir() for directories (not .file())
    val moduleDir = layout.buildDirectory.dir("outputs/module/$variantLowered")
    // Derived values resolved lazily via Provider.zip
    val zipFileName = tag.zip(commitCount.zip(commitHash) { c, h -> c to h }) { t, (count, hash) ->
        "$moduleName-$t-$count-$hash-$buildTypeLowered.zip".replace(' ', '-')
    }
    val versionName = tag.zip(commitCount.zip(commitHash) { c, h -> c to h }) { t, (count, hash) ->
        "$t ($count-$hash-$variantLowered)"
    }
    val versionCode = commitCount

    val extractDriverImportDexTask = tasks.register<ExtractDriverImportDex>(
        "extractDriverImportDex$variantCapped"
    ) {
        dependsOn("assemble$variantCapped")
        apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
        dexFile.set(layout.buildDirectory.file("intermediates/driver-importer/$variantLowered/driver-importer.dex"))
    }

    val prepareModuleFilesTask = tasks.register<Sync>("prepareModuleFiles$variantCapped") {
        group = "module"
        dependsOn("assemble$variantCapped", ":webui:buildWebUI", extractDriverImportDexTask)
        into(moduleDir)
        from(extractDriverImportDexTask.flatMap { it.dexFile })
        // Declare inputs so Gradle invalidates cache when values change
        inputs.property("moduleId", moduleId)
        inputs.property("moduleName", moduleName)
        inputs.property("versionName", versionName)
        inputs.property("versionCode", versionCode)
        inputs.property("buildType", buildTypeLowered)
        from(rootProject.layout.projectDirectory.dir("webui/build/dist/wasmJs/productionExecutable")) {
            into("webroot")
            // Exclude source maps (not needed on device) and empty composeResources dirs
            exclude("**/*.map")
        }
        from(rootProject.layout.projectDirectory.file("README.md"))
        from(layout.projectDirectory.file("src/main/cpp/external/libadrenotools/LICENSE")) {
            into("licenses")
            rename { "libadrenotools.txt" }
        }
        from(layout.projectDirectory.file("src/main/cpp/external/libadrenotools/lib/linkernsbypass/LICENSE")) {
            into("licenses")
            rename { "linkernsbypass.txt" }
        }
        from(layout.projectDirectory.file("template")) {
            exclude("module.prop", "customize.sh", "post-fs-data.sh", "service.sh")
            filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
        }
        // Stable (tagged release): point to latest so older versions discover newer releases.
        // Beta (CI build): point to the ci pre-release tag.
        val updateJson = tag.zip(gitHubUser.zip(gitHubRepo) { u, r -> u to r }) { t, (user, repo) ->
            if (t.startsWith("v")) {
                "https://github.com/$user/$repo/releases/latest/download/update.json"
            } else {
                "https://github.com/$user/$repo/releases/download/ci/update.json"
            }
        }
        from(layout.projectDirectory.file("template")) {
            include("module.prop")
            expand(
                "moduleId" to moduleId,
                "moduleName" to moduleName,
                "versionName" to versionName.get(),
                "versionCode" to versionCode.get(),
                "moduleAuthor" to moduleAuthor,
                "moduleDesc" to moduleDesc,
                "updateJson" to updateJson.get(),
            )
        }
        from(layout.projectDirectory.file("template")) {
            include("customize.sh", "post-fs-data.sh", "service.sh")
            val tokens = mapOf(
                "DEBUG" to if (buildTypeLowered == "debug") "true" else "false",
                "SONAME" to moduleLibName,
                "SUPPORTED_ABIS" to supportedAbis,
                "MODULE_ID" to moduleId,
            )
            filter<ReplaceTokens>("tokens" to tokens)
            filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
        }
        from(layout.buildDirectory.file("intermediates/stripped_native_libs/$variantLowered/strip${variantCapped}DebugSymbols/out/lib")) {
            into("lib")
        }

        doLast {
            // Remove empty composeResources sub-directories that ship no files
            // (e.g. library-generated placeholder dirs). Walk bottom-up so that
            // nested empty dirs are pruned before their parents are checked.
            destinationDir.walkBottomUp()
                .filter { it.isDirectory && it.list()?.isEmpty() == true }
                .forEach { it.delete() }

            // Use destinationDir (Sync task property) + plain Java IO instead of
            // project.fileTree / project.file to avoid capturing the script object,
            // which is required for Configuration Cache compatibility.
            destinationDir.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".sha256") }
                .forEach { f ->
                    val md = MessageDigest.getInstance("SHA-256")
                    f.forEachBlock(4096) { bytes, size -> md.update(bytes, 0, size) }
                    File(f.path + ".sha256").writeText(Hex.encodeHexString(md.digest()))
                }
        }
    }

    val zipTask = tasks.register<Zip>("zip$variantCapped") {
        group = "module"
        dependsOn(prepareModuleFilesTask)
        archiveFileName.set(zipFileName)
        destinationDirectory.set(layout.projectDirectory.file("release").asFile)
        from(moduleDir)
        // Declare inputs for CC compatibility
        inputs.property("zipFileName", zipFileName)
        inputs.property("buildType", buildTypeLowered)
        // Clean stale zips of the same variant to avoid confusion
        doFirst {
            val props = inputs.properties
            val name = props["zipFileName"] as String
            val btype = props["buildType"] as String
            destinationDirectory.get().asFile.listFiles()?.filter {
                it.name.endsWith("-$btype.zip") && it.name != name
            }?.forEach { it.delete() }
        }
    }

    // Resolve layout.projectDirectory eagerly before the task closure
    val releaseDir = layout.projectDirectory.file("release/update.json").asFile

    tasks.register("ci$variantCapped") {
        group = "module"
        dependsOn(zipTask)
        inputs.property("zipFileName", zipFileName)
        inputs.property("versionName", versionName)
        inputs.property("versionCode", versionCode)
        inputs.property("gitHubUser", gitHubUser)
        inputs.property("gitHubRepo", gitHubRepo)
        inputs.property("tag", tag)

        doLast {
            val props = inputs.properties
            val name = props["zipFileName"] as String
            val version = props["versionName"] as String
            val code = props["versionCode"] as Int
            val user = props["gitHubUser"] as String
            val repo = props["gitHubRepo"] as String
            val t = props["tag"] as String
            val jsonContent = mapOf(
                "version" to version,
                "versionCode" to code,
                "zipUrl" to "https://github.com/$user/$repo/releases/download/$t/$name",
                "changelog" to "https://github.com/$user/$repo/releases/download/$t/changelog.md"
            )
            releaseDir.writeText(JsonBuilder(jsonContent).toPrettyString())
        }
    }

    val pushTask = tasks.register<Exec>("push$variantCapped") {
        group = "module"
        dependsOn(zipTask)
        inputs.property("zipFileName", zipFileName)
        inputs.file(zipTask.flatMap { it.archiveFile })
        doFirst {
            val zipFile = inputs.files.singleFile
            commandLine("adb", "push", zipFile.path, "/data/local/tmp")
        }
    }

    val installKsuTask = tasks.register<Exec>("installKsu$variantCapped") {
        group = "module"
        dependsOn(pushTask)
        inputs.property("zipFileName", zipFileName)
        doFirst {
            val name = inputs.properties["zipFileName"] as String
            commandLine(
                "adb", "shell", "su", "-c",
                "/data/adb/ksud module install /data/local/tmp/$name"
            )
        }
    }

    val installMagiskTask = tasks.register<Exec>("installMagisk$variantCapped") {
        group = "module"
        dependsOn(pushTask)
        inputs.property("zipFileName", zipFileName)
        doFirst {
            val name = inputs.properties["zipFileName"] as String
            commandLine(
                "adb", "shell", "su", "-M", "-c",
                "magisk --install-module /data/local/tmp/$name"
            )
        }
    }

    tasks.register<Exec>("installKsuAndReboot$variantCapped") {
        group = "module"
        dependsOn(installKsuTask)
        commandLine("adb", "reboot")
    }

    tasks.register<Exec>("installMagiskAndReboot$variantCapped") {
        group = "module"
        dependsOn(installMagiskTask)
        commandLine("adb", "reboot")
    }
}
