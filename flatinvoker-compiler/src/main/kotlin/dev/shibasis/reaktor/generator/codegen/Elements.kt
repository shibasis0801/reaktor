package dev.shibasis.reaktor.generator.codegen

fun interface Generator<T> {
    fun generate(data: T): String
}


data class Function(
    val name: String,
    val params: List<Function.Param>,
    val body: String? = null,
    val returnType: String
) {
    data class Param(
        val name: String,
        val type: String
    ) {
        companion object: Generator<Param> {
            override fun generate(data: Param): String {
                return "${data.name}: ${data.type}"
            }
        }

    }

    companion object: Generator<Function> {
        override fun generate(data: Function): String {
            val params = data.params.joinToString(", ") { Param.generate(it) }
            val fn = "fun ${data.name}($params): ${data.returnType}"
            return if (data.body != null) {
                "$fn {\n${data.body}\n}"
            } else fn
        }

        fun generateHeader(data: Function): String {
            val params = data.params.joinToString(", ") { Param.generate(it) }
            return "fun ${data.name}($params): ${data.returnType}"
        }
    }
}

data class Interface(
    val name: String,
    val functions: List<Function>
) {
    companion object: Generator<Interface> {
        override fun generate(data: Interface): String {
            val functions = data.functions.joinToString("\n\n") { Function.generate(it) }
            return """
                interface ${data.name} {
                    $functions
                }
            """.trimIndent()
        }
    }
}