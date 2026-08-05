package io.github.stream29.kodex.agentstate.contract

/**
 * Normal result of one [KodexAgentState.requestResponseApi] call.
 *
 * This is deliberately separate from the state-side streaming output. The
 * active output SharedFlow is released at `OutputItemDone`, while protocol
 * completion arrives later and determines whether the runtime should issue
 * another request. Failed and incomplete terminal events are raised as
 * exceptions; a stream that ends without a terminal event is resumable.
 */
public enum class RequestFinish {
    /** The server completed this request and ended the logical turn. */
    Finish,

    /** The request requires another response or its stream ended without a terminal event. */
    Resumable,
}
