package io.github.stream29.kodex.app.settings.contract

import io.github.stream29.kodex.app.session.contract.SessionViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Stable Settings navigation destinations; render every entry in this order.
 *
 * General renders application preferences; ContextSources renders built-in/custom sources;
 * OpenAi renders authentication and account usage together; Mcp and Hooks render their lists;
 * CurrentSession renders the captured Session's settings or its unavailable state;
 * NewSession renders defaults plus the independent automatic-title configuration.
 * Each page renders its child's loading/empty/unavailable/error branches, not a root copy.
 */
public enum class SettingsPage {
    General,
    ContextSources,
    OpenAi,
    Mcp,
    Hooks,
    CurrentSession,
    NewSession,
}

/**
 * Root owner for one Settings popup.
 *
 * All three child handles are stable for this ViewModel's lifetime. The Session child remains
 * bound to the target captured when the popup opened, never to a later selected tab.
 * The implementation receives already constructed [global], [session] and [newSession] children;
 * it owns their disposal, not their borrowed stores, backend or application-scope writers.
 * Commands are confined to the owner's interaction dispatcher; no cross-thread safety is promised.
 *
 * Render navigation, a per-page scroll viewport, Close, and one shared global failure banner.
 * Render General dropdowns, Context source dialogs, OpenAI authentication overlays and Reset,
 * Current Session rename/directory/dropdowns, and New Session/default-title dropdowns directly
 * from the corresponding children. MCP and Hook dialog hosts stay mounted outside the page branch.
 * Renderer-local dropdown/focus/scroll state is not a second business-state authority.
 * Popup dismissal belongs to the host; unmounting this view does not itself close this owner.
 *
 * Login is a separate host child: Settings→Login→return retains this exact Settings owner.
 * The host mounts one MCP URL effect consumer under that retained owner; page changes and Login
 * do not recreate it. Renderer unmount cancels its captured wait; genuine owner close ends
 * VM-owned operations. Neither cancellation promises rollback of a backend-accepted command.
 */
public interface SettingsViewModel : AutoCloseable {
    /** Stable flow of the current destination; initially the factory's requested page. */
    public val selectedPage: StateFlow<SettingsPage>
    /** Stable global composition; children and failure authority are consumed directly. */
    public val global: GlobalSettingsViewModel
    /** Stable settings editor bound to the exact opening target, including revision admission. */
    public val session: SessionSettingsViewModel
    /** Stable shared new-session defaults editor, not the current virtual Session's draft. */
    public val newSession: NewSessionSettingsViewModel

    /**
     * Switches navigation once; same-page and closed-owner requests are no-ops.
     *
     * Before publishing the new page, call only the leaving page's original hide operation:
     * CurrentSession→session, Mcp→MCP, Hooks→Hook, ContextSources→context sources,
     * General→preferences, NewSession→automatic title, OpenAi→authentication.
     * In particular NewSession does not close or re-create the defaults child. Hide clears that
     * child's unaccepted interactions, not its observations or already admitted application writes;
     * changing page does not cancel MCP OAuth. Leaving OpenAI clears unconfirmed logout.
     *
     * After hide, any destination other than OpenAi requests Reset dismissal; it is ineffective
     * during live consumption and its follow-up refresh. Then publish [page]. Entering OpenAi
     * requests exactly one usage refresh; initially opening OpenAi does the same once during root
     * construction, never again in the renderer. Component construction does not refresh usage.
     *
     * No catch-all converts child failures to success. If hide/dismiss throws, the old page remains;
     * if refresh throws, the newly selected OpenAi page remains. Child async failures retain their
     * specified state/shared host failure path; accepted writes still drain outside popup scope.
     *
     * @throws Throwable if a synchronous child hide, Reset dismissal or refresh request throws;
     * cancellation propagates unchanged rather than becoming a Settings failure.
     */
    public fun selectPage(page: SettingsPage): Unit

    /**
     * Mark closed before disposing children, then call global, Session, defaults in that order.
     * Nested finally blocks attempt Session/defaults disposal even if an earlier child throws.
     * A later disposal exception replaces an earlier one, following ordinary finally semantics;
     * no rollback or exception aggregation is added. Subsequent close/select calls do nothing.
     * Session close cancels local CAS waits/releases its exact source. Global/defaults close
     * rejects new edits and releases observations, while admitted application writes may drain
     * after this method returns. Shared backend/store shutdown remains the host's responsibility.
     *
     * @throws Throwable if child disposal throws, including cancellation; the last throwing
     * disposal is propagated after remaining children have been attempted.
     */
    override fun close(): Unit
}

/** Exact inputs for one independently owned Settings popup child; neither value is retargeted. */
public data class SettingsViewModelArguments(
    /** Session identity captured when Settings opens, not a lookup key for the latest tab. */
    public val target: SessionViewModel,
    /** Initial render destination; OpenAi also requests one initial usage refresh. */
    public val initialPage: SettingsPage,
)

/**
 * Creates one Settings hierarchy bound to the captured Session target.
 *
 * The host supplies actual global/defaults editors, the exact Session source and component ports
 * (models and directory selection) through their existing typed child factories. It must not
 * create a second Global state authority. Login has a shorter, separately owned lifetime and is
 * opened only on explicit intent. The returned owner is closed by its host, not by the renderer.
 */
public fun interface SettingsViewModelFactory {
    /**
     * Construct stable children for [arguments], then a root on its initial destination.
     * Dependency/child creation failures are not replaced with empty/default settings.
     *
     * @throws IllegalStateException if the host cannot provide a source for the captured target.
     * @throws Throwable if dependency/child construction or the initial OpenAi refresh request
     * throws; cancellation propagates unchanged.
     */
    public fun create(arguments: SettingsViewModelArguments): SettingsViewModel
}
