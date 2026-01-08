package viaduct.engine.runtime.execution

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.SubqueryExecutionException

/**
 * Extensions for [EngineExecutionContext.ExecutionHandle] to enable subquery execution.
 *
 * These extensions are internal to the runtime module and allow accessing execution
 * context from the opaque ExecutionHandle interface.
 */

/**
 * Extracts [ExecutionParameters] from an opaque [EngineExecutionContext.ExecutionHandle].
 *
 * This internal extension enables [viaduct.engine.api.Engine.executeSubquery] to access
 * the execution context needed for subquery execution without exposing ExecutionParameters
 * in the public API.
 *
 * @throws SubqueryExecutionException if the handle is not a valid ExecutionParameters instance
 */
internal fun EngineExecutionContext.ExecutionHandle.asExecutionParameters(): ExecutionParameters {
    return this as? ExecutionParameters
        ?: throw SubqueryExecutionException.invalidExecutionHandle()
}
