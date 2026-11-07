package dev.shibasis.reaktor.graph.step

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.rootOf
import dev.shibasis.reaktor.graph.serviceInterceptors
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.contract.activity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull

interface Inline<I : Any, O : Any> {
    val workflowId: String
    val childGraph: Graph
    val input: InputNode<I>
    val output: Output<O>
}

suspend operator fun <I : Any, O : Any> Inline<I, O>.invoke(value: I): O =
    InlineRun(this).apply { settle(input, value) }.drive().result(output)

private class InlineRun(private val workflow: Inline<*, *>) : Outcomes {
    private val nodes = stepsOf(workflow.childGraph)
    private val handled = nodes.flatMapTo(HashSet()) { it.on.handles }
    private val states = HashMap<Output<*>, StepState>()
    private val values = HashMap<Output<*>, Any>()
    private val failures = mutableListOf<Throwable>()

    init {
        nodes.forEach { node ->
            require(node is StepNode<*> || node is EitherNode<*> || node === workflow.input) {
                "'${node.name}' in workflow '${workflow.workflowId}' is not a step its caller can run, so the workflow cannot run inline"
            }
        }
    }

    override fun state(node: Output<*>): StepState = states[node] ?: StepState.Pending

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> value(node: Output<T>): T? = values[node] as T?

    fun settle(node: Output<*>, value: Any) {
        states[node] = StepState.Succeeded
        values[node] = value
    }

    suspend fun drive(): InlineRun = apply {
        nodes.filter { it !== workflow.input }.forEach { node ->
            when (node.readiness(this)) {
                false -> states[node] = StepState.Skipped
                true -> when (node) {
                    is StepNode<*> -> attempt(node)
                    is EitherNode<*> -> settle(node, values.getValue(node.options.first { state(it) == StepState.Succeeded }))
                    else -> Unit
                }
                null -> error("'${node.name}' in workflow '${workflow.workflowId}' ran before what it waits on")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <O : Any> result(output: Output<O>): O =
        values[output] as O? ?: throw failures.lastOrNull() ?: IllegalStateException("Workflow '${workflow.workflowId}' ended without '${output.name}'")

    private suspend fun attempt(step: StepNode<*>) {
        val inputs = step.inputs.map { values[it] }
        val trace = currentCoroutineContext()[ServiceCall]?.trace?.traceId
        val scope = StepScope(listOfNotNull(trace, workflow.workflowId, step.name).joinToString("/"), 1, null) {
            error("Step '${step.name}' runs inline, so it has no checkpoint to save")
        }
        val outcome = try {
            withTimeoutOrNull(step.timeout) {
                Produced(activity(workflow.workflowId, step.name, inputs, step.effect,
                    step.identity() + (ServiceCall.ActivationAttribute to rootOf(step.graph).id.toString()),
                    step.graph.serviceInterceptors()) { step.body(scope, it) })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (step !in handled) throw failure
            failures += failure
            states[step] = StepState.Failed
            return
        }
        when {
            outcome == null && step in handled -> states[step] = StepState.TimedOut
            outcome == null -> throw StepFailed("Step '${step.name}' of workflow '${workflow.workflowId}' took longer than ${step.timeout}")
            outcome.value == null -> states[step] = StepState.Skipped
            else -> settle(step, outcome.value)
        }
    }
}

private class Produced(val value: Any?)
