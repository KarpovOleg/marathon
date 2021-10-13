package com.malinskiy.marathon.gradle

import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.TestVariant
import com.malinskiy.marathon.Marathon
import com.malinskiy.marathon.android.AndroidVendor
import com.malinskiy.marathon.android.VendorType
import com.malinskiy.marathon.android.adam.di.adamModule
import com.malinskiy.marathon.config.Configuration
import com.malinskiy.marathon.config.vendor.DEFAULT_APPLICATION_PM_CLEAR
import com.malinskiy.marathon.config.vendor.DEFAULT_AUTO_GRANT_PERMISSION
import com.malinskiy.marathon.config.vendor.DEFAULT_INIT_TIMEOUT_MILLIS
import com.malinskiy.marathon.config.vendor.DEFAULT_INSTALL_OPTIONS
import com.malinskiy.marathon.config.vendor.DEFAULT_WAIT_FOR_DEVICES_TIMEOUT
import com.malinskiy.marathon.config.vendor.VendorConfiguration
import com.malinskiy.marathon.config.vendor.android.AllureConfiguration
import com.malinskiy.marathon.config.vendor.android.FileSyncConfiguration
import com.malinskiy.marathon.config.vendor.android.ScreenRecordConfiguration
import com.malinskiy.marathon.config.vendor.android.SerialStrategy
import com.malinskiy.marathon.di.marathonStartKoin
import com.malinskiy.marathon.exceptions.ExceptionsReporter
import com.malinskiy.marathon.gradle.extensions.extractApplication
import com.malinskiy.marathon.gradle.extensions.extractTestApplication
import com.malinskiy.marathon.log.MarathonLogging
import com.malinskiy.marathon.usageanalytics.TrackActionType
import com.malinskiy.marathon.usageanalytics.UsageAnalytics
import com.malinskiy.marathon.usageanalytics.tracker.Event
import ddmlibModule
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.VerificationTask
import org.gradle.kotlin.dsl.property
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import javax.inject.Inject

private val log = MarathonLogging.logger {}

open class MarathonRunTask @Inject constructor(objects: ObjectFactory) : DefaultTask(), VerificationTask {
    @Input
    val flavorName: Property<String> = objects.property()

    @Internal
    val applicationVariant: Property<BaseVariant> = objects.property()

    @Internal
    val testVariant: Property<TestVariant> = objects.property()

    @InputDirectory
    @PathSensitive(PathSensitivity.NAME_ONLY)
    val sdk: DirectoryProperty = objects.directoryProperty()

    @Internal
    val marathonExtension: Property<MarathonExtension> = objects.property()

    @Internal
    val exceptionsTracker: Property<ExceptionsReporter> = objects.property()

    private var ignoreFailure: Boolean = false

    @OutputDirectory
    var fakeLockingOutput = File(project.rootProject.buildDir, "fake-marathon-locking-output")

    @TaskAction
    fun runMarathon() {
        val tracker = exceptionsTracker.get()
        val extensionConfig = marathonExtension.get()
        val instrumentationApk = testVariant.get().extractTestApplication()
        val applicationApk = applicationVariant.get().extractApplication()

        val baseOutputDir = extensionConfig.baseOutputDir?.let { File(it) } ?: File(project.buildDir, "reports/marathon")
        val output = File(baseOutputDir, flavorName.get())

        val (vendorConfiguration, modules) = createAndroid(extensionConfig, applicationApk, instrumentationApk)

        val cnf = Configuration(
            name = extensionConfig.name,
            outputDir = output,
            analyticsConfiguration = extensionConfig.analyticsConfiguration?.toAnalyticsConfiguration(),
            poolingStrategy = extensionConfig.poolingStrategy?.toStrategy(),
            extensionConfig.shardingStrategy?.toStrategy(),
            extensionConfig.sortingStrategy?.toStrategy(),
            extensionConfig.batchingStrategy?.toStrategy(),
            extensionConfig.flakinessStrategy?.toStrategy(),
            extensionConfig.retryStrategy?.toStrategy(),
            extensionConfig.filteringConfiguration?.toFilteringConfiguration(),
            extensionConfig.ignoreFailures,
            extensionConfig.isCodeCoverageEnabled,
            extensionConfig.fallbackToScreenshots,
            extensionConfig.strictMode,
            extensionConfig.uncompletedTestRetryQuota,
            extensionConfig.testClassRegexes?.map { it.toRegex() },
            extensionConfig.includeSerialRegexes?.map { it.toRegex() },
            extensionConfig.excludeSerialRegexes?.map { it.toRegex() },
            extensionConfig.testBatchTimeoutMillis,
            extensionConfig.testOutputTimeoutMillis,
            extensionConfig.debug,
            extensionConfig.screenRecordingPolicy,
            vendorConfiguration = vendorConfiguration,
            analyticsTracking = extensionConfig.analyticsTracking,
            deviceInitializationTimeoutMillis = extensionConfig.deviceInitializationTimeoutMillis
        )

        val androidConfiguration = cnf.vendorConfiguration as? VendorConfiguration.AndroidConfiguration

        log.info { "Run instrumentation tests ${androidConfiguration?.testApplicationOutput} for app ${androidConfiguration?.applicationOutput}" }
        log.debug { "Output: ${cnf.outputDir}" }
        log.debug { "Ignore failures: ${cnf.ignoreFailures}" }

        UsageAnalytics.enable = cnf.analyticsTracking
        UsageAnalytics.USAGE_TRACKER.trackEvent(Event(TrackActionType.RunType, "gradle"))
        try {
            val application = marathonStartKoin(cnf, modules)
            val marathon: Marathon = application.koin.get()

            val success = marathon.run()
            tracker.end()
            val shouldReportFailure = !cnf.ignoreFailures
            if (!success && shouldReportFailure) {
                throw GradleException("Tests failed! See ${cnf.outputDir}/html/index.html")
            }
        } finally {
            stopKoin()
        }
    }

    private fun createAndroid(
        extension: MarathonExtension,
        applicationApk: File?,
        instrumentationApk: File
    ): Pair<VendorConfiguration.AndroidConfiguration, List<Module>> {
        val autoGrantPermission = extension.autoGrantPermission ?: DEFAULT_AUTO_GRANT_PERMISSION
        val instrumentationArgs = extension.instrumentationArgs
        val applicationPmClear = extension.applicationPmClear ?: DEFAULT_APPLICATION_PM_CLEAR
        val testApplicationPmClear = extension.testApplicationPmClear ?: DEFAULT_APPLICATION_PM_CLEAR
        val adbInitTimeout = extension.adbInitTimeout ?: DEFAULT_INIT_TIMEOUT_MILLIS
        val installOptions = extension.installOptions ?: DEFAULT_INSTALL_OPTIONS
        val screenRecordConfiguration = extension.screenRecordConfiguration ?: ScreenRecordConfiguration()
        val serialStrategy = extension.serialStrategy ?: SerialStrategy.AUTOMATIC
        val waitForDevicesTimeoutMillis = extension.waitForDevicesTimeoutMillis ?: DEFAULT_WAIT_FOR_DEVICES_TIMEOUT
        val allureConfiguration = extension.allureConfiguration ?: AllureConfiguration()

        val implementationModules = when (extension.vendor ?: VendorType.DDMLIB) {
            VendorType.DDMLIB -> listOf(ddmlibModule)
            VendorType.ADAM -> listOf(adamModule)
        }

        val androidConfiguration = VendorConfiguration.AndroidConfiguration(
            androidSdk = sdk.get().asFile,
            applicationOutput = applicationApk,
            testApplicationOutput = instrumentationApk,
            autoGrantPermission = autoGrantPermission,
            instrumentationArgs = instrumentationArgs,
            applicationPmClear = applicationPmClear,
            testApplicationPmClear = testApplicationPmClear,
            adbInitTimeoutMillis = adbInitTimeout,
            installOptions = installOptions,
            screenRecordConfiguration = screenRecordConfiguration,
            serialStrategy = serialStrategy,
            waitForDevicesTimeoutMillis = waitForDevicesTimeoutMillis,
            allureConfiguration = allureConfiguration,
            fileSyncConfiguration = extension.fileSyncConfiguration ?: FileSyncConfiguration(),
        )

        val androidVendor = AndroidVendor + implementationModules + module { single { androidConfiguration } }
        return Pair(androidConfiguration, androidVendor)
    }

    override fun getIgnoreFailures(): Boolean = ignoreFailure

    override fun setIgnoreFailures(ignoreFailures: Boolean) {
        ignoreFailure = ignoreFailures
    }
}
