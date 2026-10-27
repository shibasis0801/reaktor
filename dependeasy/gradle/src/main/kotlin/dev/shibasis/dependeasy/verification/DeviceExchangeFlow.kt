package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.model.*
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

class DevicePhone internal constructor(
    @get:Input val platform: DevicePlatform,
    @get:Input val appOutput: String,
    @get:Input val flowPaths: Provider<List<String>>,
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val flowFiles: FileCollection,
    @get:Internal val appFile: Provider<RegularFile>,
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val applicationOutputs: FileCollection,
    @get:Input @get:Optional val serial: Provider<String>,
    @get:OutputFile val contractFile: Provider<RegularFile>,
)

@DisableCachingByDefault(because = "Exchanges real messages between physical devices")
abstract class DeviceExchangeFlow : DeviceTask() {
    @get:Nested val phones = mutableListOf<DevicePhone>()
    @get:Input abstract val receive: Property<String>
    @get:Input abstract val send: Property<String>
    @get:Input abstract val token: Property<String>
    @get:Input abstract val sendAfterSeconds: Property<Long>
    @get:Internal override val kernelSpec get() = KernelSpec.DeviceExchange(
        phones.map { flowSpec(it.platform, it.appOutput, it.flowPaths.get(), it.contractFile.get().asFile) },
        receive.get(), send.get(), token.get(), sendAfterSeconds.get())
    @TaskAction fun run() {
        val spec = kernelSpec
        val executions = phones.zip(spec.phones) { phone, flow ->
            execution(flow, phone.appFile.orNull?.asFile, phone.serial.orNull, reportDirectory.get().asFile.resolve(phone.platform.value), phone.applicationOutputs.files)
        }
        DeviceExchangeKernel.run(spec, executions, ::execute) { logger.lifecycle(it) }
    }
}
