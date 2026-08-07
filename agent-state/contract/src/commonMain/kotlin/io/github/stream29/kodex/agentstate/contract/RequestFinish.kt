package io.github.stream29.kodex.agentstate.contract

/**
 * Normal result of one [KodexAgentState.requestResponseApi] call.
 *
 * This is deliberately separate from the state-side streaming output. The
 * active output SharedFlow is released at `OutputItemDone`, while protocol
 * completion arrives later and determines whether the runtime should issue
 * another request. A failed terminal event or a stream that ends without a
 * terminal event is retryable; an incomplete terminal event is raised as an
 * exception.
 */
public enum class RequestFinish {
    /** The server completed this request and ended the logical turn. */
    Finish,

    /** The server completed this request and explicitly requested another response. */
    Continue,

    /** The request failed transiently and may be attempted again. */
    Retryable,
}
