package org.modelix.incremental

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.internal.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.collections.HashSet
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.Synchronized

class IncrementalEngine(var maxSize: Int = 100_000, var maxActiveValidations: Int = (maxSize / 100).coerceAtMost(100)) : IIncrementalEngine, IStateVariableGroup, IDependencyListener {

    private val graph = DependencyGraph(this)
    private var activeEvaluation: Evaluation? = null
    private var autoValidator: Job? = null
    private var disposed = false
    private val engineScope = CoroutineScope(Dispatchers.Default)

    init {
        DependencyTracking.registerListener(this)
    }

    fun getGraphSize() = graph.getSize()

    private fun checkDisposed() {
        if (disposed) throw IllegalStateException("engine is disposed")
    }

    override fun <T> readStateVariable(call: IStateVariableDeclaration<T>): T {
        checkDisposed()
        val engineValueKey = InternalStateVariableReference(this, call)
        DependencyTracking.accessed(engineValueKey)
        return update(engineValueKey)
    }

    override fun <T> readStateVariables(calls: List<IStateVariableDeclaration<T>>): List<T> {
        checkDisposed()
        val keys = calls.map { InternalStateVariableReference(this, it) }
        keys.forEach { DependencyTracking.accessed(it) }
        return keys.map { update(it) }
    }

    @Synchronized
    private fun <T> update(engineValueKey: InternalStateVariableReference<T>): T {
        checkDisposed()
        var value: T? = null
        var exception: Throwable? = null
        var state: ECacheEntryState = ECacheEntryState.NEW
        val node: DependencyGraph.InternalStateNode<T>
        var evaluation: Evaluation? = null

        node = graph.getOrAddNode(engineValueKey) as DependencyGraph.InternalStateNode<T>
        state = node.state
        when (state) {
            ECacheEntryState.VALID -> {
                return node.getValue().getValue()
            }
            ECacheEntryState.FAILED -> {
                throw (if (node is DependencyGraph.ComputationNode) node.lastException else null) ?: RuntimeException()
            }
            else -> {
                if (node is DependencyGraph.ComputationNode) {
                    if (graph.getSize() >= maxSize) {
                        graph.shrinkGraph(maxSize - maxSize / 20)
                        //graph.shrinkGraph(maxSize - 1)
                    }
                    val decl = engineValueKey.decl
                    if (decl is IComputationDeclaration) {
                        evaluation = Evaluation(engineValueKey, decl, activeEvaluation)
                        evaluation!!.detectCycle()
                        try {
                            activeEvaluation = evaluation

                            // This dependency has to be added here, because the node may be removed from the graph
                            // before the parent finishes evaluation and then the transitive dependencies are lost.
                            evaluation!!.parent?.let { graph.getOrAddNode(it.dependencyKey).addDependency(node) }

                            if (state == ECacheEntryState.VALIDATING) {
                                TODO("shouldn't happen")
                            } else {
                                node.startValidation()
                                try {
                                    val value = decl.invoke(IncrementalFunctionContext(node))
                                    node.validationSuccessful(value, evaluation.dependencies)
                                    return value
                                } catch (e : Throwable) {
                                    node.validationFailed(e, evaluation.dependencies)
                                    throw e
                                }
                            }
                        } finally {
                            activeEvaluation = evaluation.parent
                        }
                    } else {
                        TODO("run triggers")
                    }
                } else {
                    return node.getValue().getValue()
                }
            }
        }
    }

    @Synchronized
    override fun <T> activate(call: IncrementalFunctionCall<T>): IActiveOutput<T> {
        checkDisposed()
        if (autoValidator == null) {
            autoValidator = engineScope.launch {
                while (!disposed) {
                    val key = graph.autoValidationChannel.receive()
                    update(key)
                }
            }
        }

        val key = InternalStateVariableReference(this@IncrementalEngine, call)
        val node = graph.getOrAddNode(key) as DependencyGraph.ComputationNode<T>
        node.setAutoValidate(true)
        graph.autoValidationChannel.trySend(key)
        return ObservedOutput<T>(key)
    }

    override fun accessed(key: IStateVariableReference<*>) {
        val evaluation = activeEvaluation ?: return
        evaluation.dependencies += key
    }

    @Synchronized
    override fun modified(key: IStateVariableReference<*>) {
        if (key is InternalStateVariableReference<*> && key.engine == this) return
        for (group in key.iterateGroups()) {
            val node = graph.getNode(group)
            if (node != null) {
                node.invalidate()
                break
            }
        }
    }

    override fun parentGroupChanged(childGroup: IStateVariableGroup) {
        TODO("Not yet implemented")
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        engineScope.cancel(CancellationException("Engine disposed"))
        DependencyTracking.removeListener(this)
    }

    override fun getGroup(): IStateVariableGroup? {
        return null
    }

    @Synchronized
    override fun flush() {
        checkDisposed()
        for (autoValidation in graph.autoValidations.filter { it.state != ECacheEntryState.VALID }) {
            update(autoValidation.key)
        }
    }

    override fun <T> readStateVariable(call: IStateVariableDeclaration<T>, callback: (T) -> Unit) {
        engineScope.launch { callback(readStateVariable(call)) }
    }

    override fun <T> readStateVariables(calls: List<IStateVariableDeclaration<T>>, callback: (List<T>) -> Unit) {
        engineScope.launch { callback(readStateVariables(calls)) }
    }

    override fun <T> activate(call: IncrementalFunctionCall<T>, callback: (IActiveOutput<T>) -> Unit) {
        engineScope.launch { callback(activate(call)) }
    }

    override fun flush(callback: () -> Unit) {
        engineScope.launch {
            flush()
            callback()
        }
    }

    private inner class IncrementalFunctionContext<RetT>(val node: DependencyGraph.ComputationNode<RetT>) : IIncrementalFunctionContext<RetT> {
        override fun readOwnStateVariable(): Optional<RetT> {
            return node.getValue()
        }

        override fun <T> readStateVariable(key: IStateVariableReference<T>): Optional<T> {
            TODO("Not yet implemented")
        }

        override fun <T> writeStateVariable(ref: IStateVariableReference<T>, value: T) {
            TODO("Not yet implemented")
        }
    }

    private class Evaluation(
        val dependencyKey: IStateVariableReference<*>,
        val call: IComputationDeclaration<*>,
        val parent: Evaluation?,
    ) : AbstractCoroutineContextElement(Evaluation) {
        companion object Key : CoroutineContext.Key<Evaluation>

        val dependencies: MutableSet<IStateVariableReference<*>> = HashSet()

        fun getEvaluations(): List<Evaluation> {
            return (parent?.getEvaluations() ?: emptyList()) + this
        }

        fun detectCycle() {
            var current = parent
            while (current != null) {
                if (current.dependencyKey == dependencyKey) {
                    val activeEvaluations = parent!!.getEvaluations()
                    val cycleStart = activeEvaluations.indexOfLast { it.dependencyKey == dependencyKey }
                    if (cycleStart != -1) {
                        throw DependencyCycleException(activeEvaluations.drop(cycleStart).map { it.call })
                    }
                }
                current = current.parent
            }
        }
    }

    @Synchronized
    private fun setAutoValidate(key: IStateVariableReference<*>, value: Boolean) {
        val node = (graph.getNode(key) ?: return) as DependencyGraph.ComputationNode<*>
        // TODO there could be multiple instances for the same key
        node.setAutoValidate(value)
    }

    private inner class ObservedOutput<E>(val key: IStateVariableReference<E>) : IActiveOutput<E> {
        override fun deactivate() {
            setAutoValidate(key, false)
        }

        protected fun finalize() {
            engineScope.launch { deactivate() }
        }
    }
}