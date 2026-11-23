package michi.versioning

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Computes the version once, in the root project, from the latest reachable
 * `vX.Y.Z` tag. Modules read the `michiVersion` extension instead of running
 * `git describe` again.
 */
class VersioningPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        require(project == project.rootProject) {
            "The michi.versioning plugin must be applied to the root project."
        }

        val extension = project.extensions.create("michiVersion", MichiVersioningExtension::class.java)
        val overrideName = project.findProperty("michiVersionName") as? String
        val overrideCode = project.findProperty("michiVersionCode") as? String
        val requireTagVersion = project.findProperty("requireTagVersion")
            ?.toString()
            ?.toBoolean() == true
        val needsGit = overrideName.isNullOrBlank() || overrideCode.isNullOrBlank()
        val describe = if (needsGit) readGitDescribe(project) else null
        val resolution = AppVersionCalculator.resolve(
            describe = describe,
            overrideName = overrideName,
            overrideCode = overrideCode,
            requireTagVersion = requireTagVersion,
        )

        extension.versionName.set(resolution.version.versionName)
        extension.versionCode.set(resolution.version.versionCode)
        extension.iosMarketingVersion.set(resolution.version.iosMarketingVersion)

        resolution.warning?.let { project.logger.warn("Michi version: $it") }

        project.tasks.register("writeIosVersionConfig", WriteIosVersionConfigTask::class.java) {
            marketingVersion.set(extension.iosMarketingVersion)
            versionCode.set(extension.versionCode)
            fullVersionName.set(extension.versionName)
            outputFile.set(project.layout.projectDirectory.file("iosApp/Configuration/Version.xcconfig"))
        }

        // Release workflow guard: prints only the versionName so it can be
        // compared with the pushed tag (use `./gradlew -q printVersion`).
        project.tasks.register("printVersion") {
            group = "help"
            description = "Prints the resolved versionName."
            doLast {
                println(extension.versionName.get())
            }
        }
    }

    private fun readGitDescribe(project: Project): String? {
        return try {
            val exec = project.providers.exec {
                workingDir(project.rootDir)
                commandLine(
                    "git",
                    "describe",
                    "--tags",
                    "--match",
                    "v[0-9]*",
                    "--long",
                    "--abbrev=7",
                )
                isIgnoreExitValue = true
            }
            if (exec.result.get().exitValue != 0) {
                null
            } else {
                exec.standardOutput.asText.get().trim().ifBlank { null }
            }
        } catch (_: Exception) {
            null
        }
    }
}
