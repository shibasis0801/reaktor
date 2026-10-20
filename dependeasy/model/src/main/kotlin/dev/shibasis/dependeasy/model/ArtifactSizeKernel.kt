package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.model.tools.Compression
import java.io.File
import java.io.OutputStream

object ArtifactSizeKernel {
    fun measure(spec: KernelSpec.ArtifactSize, node: NodeId, base: File, inputs: Collection<File>): ArtifactSizeResult {
        val files = inputs.filter { it.isFile }.sortedBy { it.absolutePath }
        val measurements = files.map { file ->
            val gzip = compressedSize { Compression.gzip(file, it) }
            val brotli = if (file.extension in setOf("css", "html", "js", "json", "mjs", "svg"))
                compressedSize { Compression.brotli(file, it) } else file.length()
            ArtifactMeasurement(file.relativeTo(base).invariantSeparatorsPath,
                file.length(), gzip, brotli, BuildModelReader.digest(file))
        }
        val missing = mutableListOf<String>()
        val values = spec.metrics.associate { selector ->
            val pattern = Regex(selector.pathPattern)
            val selected = measurements.filter { pattern.matches(it.path) }
            if (selector.required && selected.isEmpty()) missing.add(selector.name)
            val value = when (selector.measure) {
                SizeMeasure.Bytes -> selected.sumOf { it.bytes }
                SizeMeasure.Gzip -> selected.sumOf { it.gzip }
                SizeMeasure.Brotli -> selected.sumOf { it.brotli }
                SizeMeasure.Count -> selected.size.toLong()
                SizeMeasure.ContentMatches -> selected.sumOf {
                    Regex(requireNotNull(selector.contentPattern)).findAll(base.resolve(it.path).readText()).count().toLong()
                }
            }
            selector.name to value
        }
        return ArtifactSizeResult(node = node, artifacts = measurements, metrics = values, missing = missing, budgets = spec.budgets)
    }

    private fun compressedSize(write: (OutputStream) -> Unit): Long {
        var count = 0L
        val output = object : OutputStream() {
            override fun write(value: Int) { count++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { count += length }
        }
        write(output)
        return count
    }
}
