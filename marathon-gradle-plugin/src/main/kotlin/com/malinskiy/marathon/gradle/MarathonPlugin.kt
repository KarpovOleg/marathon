package com.malinskiy.marathon.gradle

import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.api.BaseVariantOutput
import com.android.build.gradle.api.TestVariant
import com.malinskiy.marathon.gradle.extensions.executeGradleCompat
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.kotlin.dsl.closureOf
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipFile

class MarathonPlugin : Plugin<Project> {

    override fun apply(project: Project) {
//        log.info { "Applying marathon plugin" }

        val extension: MarathonExtension = project.extensions.create("marathon", MarathonExtension::class.java, project)

        project.afterEvaluate {
            val cli = prepareDistribution(project)

            val appPlugin = project.plugins.findPlugin(AppPlugin::class.java)
            val libraryPlugin = project.plugins.findPlugin(LibraryPlugin::class.java)

            if (appPlugin == null && libraryPlugin == null) {
                throw IllegalStateException("Android plugin is not found")
            }

            val marathonTask: Task = project.task(TASK_PREFIX, closureOf<Task> {
                group = JavaBasePlugin.VERIFICATION_GROUP
                description = "Runs all the instrumentation test variations on all the connected devices"
            })

            val appExtension = extensions.findByType(AppExtension::class.java)
            val libraryExtension = extensions.findByType(LibraryExtension::class.java)

            if (appExtension == null && libraryExtension == null) {
                throw IllegalStateException("No TestedExtension is found")
            }
            val testedExtension = appExtension ?: libraryExtension

            val conf = extensions.getByName("marathon") as? MarathonExtension ?: MarathonExtension(project)

            testedExtension!!.testVariants.all {
//                log.info { "Applying marathon for ${this.baseName}" }
                val testTaskForVariant = createTask(this, project, conf, testedExtension.sdkDirectory, cli)
                marathonTask.dependsOn(testTaskForVariant)
            }
        }
    }

    private fun prepareDistribution(project: Project): File {
        val buildDir = project.buildDir
        val marathonBuildDir = File(buildDir, "marathon").apply { mkdirs() }

        //Unzip marathon distribution
        val marathonZip = File(marathonBuildDir, "marathon-cli.zip")
        marathonZip.outputStream().buffered().use {
            MarathonPlugin::class.java.getResourceAsStream("/marathon-cli.zip").copyTo(it)
        }

        marathonBuildDir.listFiles()?.forEach {
            if (it.isDirectory) {
                it.delete()
            }
        }
        ZipFile(marathonZip).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                zip.getInputStream(entry).use { input ->
                    val filePath = marathonBuildDir.canonicalPath + File.separator + entry.name
                    val file = File(filePath)
                    if (!entry.isDirectory) {
                        file.parentFile.mkdirs()
                        file.outputStream().buffered().use {
                            input.copyTo(it)
                        }
                    } else {
                        file.mkdirs()
                    }
                }
            }
        }
        marathonBuildDir.listFiles()?.forEach {
            if (it.isDirectory) {
                it.renameTo(File(it.parent, "cli"))
            }
        }

        return Paths.get(marathonBuildDir.canonicalPath, "cli", "bin", "marathon").apply {
            val permissions = Files.getPosixFilePermissions(this)
            Files.setPosixFilePermissions(this, permissions + PosixFilePermission.OWNER_EXECUTE)
        }.toFile()
    }

    companion object {
        private fun createTask(
            variant: TestVariant,
            project: Project,
            config: MarathonExtension,
            sdkDirectory: File,
            cli: File,
        ): MarathonRunTask {
            checkTestVariants(variant)

            val marathonTask = project.tasks.create("$TASK_PREFIX${variant.name.capitalize()}", MarathonRunTask::class.java)

            variant.testedVariant.outputs.all {
                val testedOutput = this
//                log.info { "Processing output $testedOutput" }

                checkTestedVariants(testedOutput)
                marathonTask.configure(closureOf<MarathonRunTask> {
                    group = JavaBasePlugin.VERIFICATION_GROUP
                    description = "Runs instrumentation tests on all the connected devices for '${variant.name}' " +
                        "variation and generates a report with screenshots"
                    flavorName.set(variant.name)
                    applicationVariant.set(variant.testedVariant)
                    testVariant.set(variant)
                    marathonExtension.set(config)
                    sdk.set(sdkDirectory)
                    outputs.upToDateWhen { false }
                    executeGradleCompat(
                        exec = {
                            dependsOn(variant.testedVariant.assembleProvider, variant.assembleProvider)
                        },
                        fallbacks = listOf {
                            @Suppress("DEPRECATION")
                            dependsOn(variant.testedVariant.assemble, variant.assemble)
                        }
                    )
                    executable(cli.absolutePath)
                })
            }

            return marathonTask
        }

        private fun checkTestVariants(testVariant: TestVariant) {
            if (testVariant.outputs.size > 1) {
                throw UnsupportedOperationException("The Marathon plugin does not support abi/density splits for test APKs")
            }

        }

        /**
         * Checks that if the base variant contains more than one outputs (and has therefore splits), it is the universal APK.
         * Otherwise, we can test the single output. This is a workaround until Fork supports test & app splits properly.
         *
         * @param baseVariant the tested variant
         */
        private fun checkTestedVariants(baseVariantOutput: BaseVariantOutput) {
            if (baseVariantOutput.outputs.size > 1) {
                throw UnsupportedOperationException(
                    "The Marathon plugin does not support abi splits for app APKs, " +
                        "but supports testing via a universal APK. "
                        + "Add the flag \"universalApk true\" in the android.splits.abi configuration."
                )
            }

        }

        /**
         * Task name prefix.
         */
        private const val TASK_PREFIX = "marathon"
    }
}
