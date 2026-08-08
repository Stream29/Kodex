package io.github.stream29.kodex.openai.accountusage

import io.github.stream29.kodex.cli.auth.KodexAuthState
import io.github.stream29.kodex.cli.auth.KodexAuthStore
import io.github.stream29.kodex.openai.CodexAccountUsageResponse
import io.github.stream29.kodex.openai.CodexRateLimitResetConsumeCode
import io.github.stream29.kodex.openai.CodexRateLimitResetConsumeRequest
import io.github.stream29.kodex.openai.CodexRateLimitResetCreditsResponse
import io.github.stream29.kodex.openai.CodexTokenUsageProfile
import io.github.stream29.kodex.openai.CodexTokenUsageProfileStats
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.getOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class CodexAccountUsageStoreImpl(
    private val client: OpenAiClient,
    private val authStore: KodexAuthStore,
) : CodexAccountUsageStore {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val operationMutex = Mutex()
    private val attemptAccountKeys = linkedMapOf<String, AccountKey>()
    private var snapshotAccountKey: AccountKey? = null

    override val state: StateFlow<CodexAccountUsageState>
        field = MutableStateFlow<CodexAccountUsageState>(
            CodexAccountUsageState.Unavailable("Codex account usage has not been loaded."),
        )

    init {
        scope.launch {
            var observedAccountKey: AccountKey? = null
            var observedAuth = false
            authStore.state.collectLatest { authState ->
                val accountKey = (authState as? KodexAuthState.Authenticated)
                    ?.value
                    ?.accountKey()
                if (!observedAuth || accountKey != observedAccountKey) {
                    observedAuth = true
                    observedAccountKey = accountKey
                    state.value = when (authState) {
                        is KodexAuthState.Authenticated -> CodexAccountUsageState.Loading()
                        is KodexAuthState.Unavailable ->
                            CodexAccountUsageState.Unavailable(authState.message)
                    }
                }
                when (authState) {
                    is KodexAuthState.Authenticated -> refresh(authState.value, accountKey!!)
                    is KodexAuthState.Unavailable -> operationMutex.withLock {
                        snapshotAccountKey = null
                        attemptAccountKeys.clear()
                    }
                }
            }
        }
    }

    override suspend fun refresh() {
        when (val authState = authStore.state.value) {
            is KodexAuthState.Authenticated ->
                refresh(authState.value, authState.value.accountKey())

            is KodexAuthState.Unavailable -> {
                state.value = CodexAccountUsageState.Unavailable(authState.message)
                operationMutex.withLock {
                    snapshotAccountKey = null
                    attemptAccountKeys.clear()
                }
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun createResetAttempt(creditId: String?): CodexRateLimitResetAttempt =
        operationMutex.withLock {
            val auth = requireAuthenticated()
            val accountKey = auth.accountKey()
            check(
                snapshotAccountKey == accountKey &&
                    state.value is CodexAccountUsageState.Available,
            ) {
                "Refresh Codex account usage before using a reset."
            }
            val attempt = CodexRateLimitResetAttempt(
                idempotencyKey = Uuid.generateV7().toString(),
                creditId = creditId?.takeIf(String::isNotBlank),
            )
            attemptAccountKeys.clear()
            attemptAccountKeys[attempt.idempotencyKey] = accountKey
            attempt
        }

    override suspend fun consumeResetAttempt(
        attempt: CodexRateLimitResetAttempt,
    ): CodexRateLimitResetOutcome =
        operationMutex.withLock {
            require(attempt.idempotencyKey.isNotBlank()) {
                "A reset attempt requires a non-blank idempotency key."
            }
            val account = requireAuthenticated()
            val accountKey = account.accountKey()
            check(attemptAccountKeys[attempt.idempotencyKey] == accountKey) {
                "This reset attempt does not belong to the current Codex account."
            }
            val previous = state.value.snapshotOrNull()
            check(previous != null && snapshotAccountKey == accountKey) {
                "Refresh Codex account usage before using a reset."
            }
            state.value = CodexAccountUsageState.Redeeming(previous, attempt)

            val response = try {
                client.consumeCodexRateLimitResetCredit(
                    request = CodexRateLimitResetConsumeRequest(
                        redeemRequestId = attempt.idempotencyKey,
                        creditId = attempt.creditId,
                    ),
                    expectedAccount = account,
                ).getOrThrow()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (currentAccountKey() == accountKey) {
                    state.value = CodexAccountUsageState.Failed(
                        message = failure.usageFailureMessage("Couldn't use the usage limit reset."),
                        previous = previous,
                    )
                } else {
                    transitionToCurrentAuth()
                }
                throw failure
            }

            attemptAccountKeys.remove(attempt.idempotencyKey)
            val outcome = response.code.toOutcome()
            if (currentAccountKey() != accountKey) {
                transitionToCurrentAuth()
                throw IllegalStateException(
                    "The authenticated Codex account changed while the reset was being used.",
                )
            }

            try {
                val snapshot = fetchSnapshot()
                if (currentAccountKey() == accountKey) {
                    snapshotAccountKey = accountKey
                    state.value = CodexAccountUsageState.Available(snapshot)
                } else {
                    transitionToCurrentAuth()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (currentAccountKey() == accountKey) {
                    state.value = CodexAccountUsageState.Failed(
                        message = failure.usageFailureMessage(
                            "The reset result was received, but usage couldn't be refreshed.",
                        ),
                        previous = previous,
                    )
                } else {
                    transitionToCurrentAuth()
                }
            }
            outcome
        }

    override fun close(): Unit = scope.cancel()

    private suspend fun refresh(
        account: OpenAiSubscriptionAuthState,
        accountKey: AccountKey,
    ) {
        operationMutex.withLock {
            if (snapshotAccountKey != null && snapshotAccountKey != accountKey) {
                attemptAccountKeys.clear()
            }
            val previous = state.value.snapshotOrNull()
                ?.takeIf { snapshotAccountKey == accountKey }
            state.value = CodexAccountUsageState.Loading(previous)
            try {
                val snapshot = fetchSnapshot()
                if (authStore.state.value.matches(account, accountKey)) {
                    snapshotAccountKey = accountKey
                    state.value = CodexAccountUsageState.Available(snapshot)
                } else {
                    transitionToCurrentAuth()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (authStore.state.value.matches(account, accountKey)) {
                    state.value = CodexAccountUsageState.Failed(
                        message = failure.usageFailureMessage("Couldn't load Codex account usage."),
                        previous = previous,
                    )
                } else {
                    transitionToCurrentAuth()
                }
            }
        }
    }

    private suspend fun fetchSnapshot(): CodexAccountUsageSnapshot = coroutineScope {
        val usageRequest = async {
            client.getCodexAccountUsage().getOrThrow()
        }
        val resetCreditsRequest = async {
            optionalSection {
                client.listCodexRateLimitResetCredits().getOrThrow()
            }
        }
        val tokenUsageRequest = async {
            optionalSection {
                client.getCodexTokenUsageProfile().getOrThrow()
            }
        }

        val usage = usageRequest.await()
        val resetCredits = resetCreditsRequest.await()
        val tokenUsage = tokenUsageRequest.await()
        buildAccountUsageSnapshot(
            usage = usage,
            resetCredits = resetCredits.getOrNull(),
            tokenUsage = tokenUsage.getOrNull(),
            unavailableSections = buildSet {
                if (resetCredits.isFailure) add(CodexAccountUsageSection.ResetCreditDetails)
                if (tokenUsage.isFailure) add(CodexAccountUsageSection.TokenUsage)
            },
            fetchedAt = Clock.System.now(),
        )
    }

    private fun requireAuthenticated(): OpenAiSubscriptionAuthState =
        when (val authState = authStore.state.value) {
            is KodexAuthState.Authenticated -> authState.value
            is KodexAuthState.Unavailable ->
                throw IllegalStateException("OpenAI authentication is unavailable: ${authState.message}")
        }

    private fun currentAccountKey(): AccountKey? =
        (authStore.state.value as? KodexAuthState.Authenticated)?.value?.accountKey()

    private fun transitionToCurrentAuth() {
        when (val authState = authStore.state.value) {
            is KodexAuthState.Authenticated -> {
                if (authState.value.accountKey() != snapshotAccountKey) {
                    snapshotAccountKey = null
                    attemptAccountKeys.clear()
                    state.value = CodexAccountUsageState.Loading()
                }
            }

            is KodexAuthState.Unavailable -> {
                snapshotAccountKey = null
                attemptAccountKeys.clear()
                state.value = CodexAccountUsageState.Unavailable(authState.message)
            }
        }
    }
}

/** Creates a live, account-isolated Codex usage store. */
public fun CodexAccountUsageStore(
    client: OpenAiClient,
    authStore: KodexAuthStore,
): CodexAccountUsageStore =
    CodexAccountUsageStoreImpl(
        client = client,
        authStore = authStore,
    )

internal fun buildAccountUsageSnapshot(
    usage: CodexAccountUsageResponse,
    resetCredits: CodexRateLimitResetCreditsResponse?,
    tokenUsage: CodexTokenUsageProfile?,
    unavailableSections: Set<CodexAccountUsageSection> = emptySet(),
    fetchedAt: Instant,
): CodexAccountUsageSnapshot {
    val rateLimits = buildList {
        usage.rateLimit?.let { status ->
            add(
                CodexAccountRateLimit(
                    name = "Codex",
                    meteredFeature = "codex",
                    allowed = status.allowed,
                    limitReached = status.limitReached,
                    primaryWindow = status.primaryWindow?.toDomain(),
                    secondaryWindow = status.secondaryWindow?.toDomain(),
                ),
            )
        }
        usage.additionalRateLimits.orEmpty().forEach { additional ->
            additional.rateLimit?.let { status ->
                add(
                    CodexAccountRateLimit(
                        name = additional.limitName,
                        meteredFeature = additional.meteredFeature,
                        allowed = status.allowed,
                        limitReached = status.limitReached,
                        primaryWindow = status.primaryWindow?.toDomain(),
                        secondaryWindow = status.secondaryWindow?.toDomain(),
                    ),
                )
            }
        }
    }
    val detailedCredits = resetCredits?.credits
        ?.asSequence()
        ?.filter { credit ->
            credit.resetType == CodexRateLimitResetType &&
                credit.status == AvailableResetCreditStatus
        }
        ?.map { credit ->
            CodexRateLimitResetCredit(
                id = credit.id,
                grantedAt = credit.grantedAt.toInstantOrNull(),
                expiresAt = credit.expiresAt?.toInstantOrNull(),
                title = credit.title?.trim()?.takeIf(String::isNotEmpty),
                description = credit.description?.trim()?.takeIf(String::isNotEmpty),
            )
        }
        ?.sortedBy { credit -> credit.expiresAt ?: Instant.DISTANT_FUTURE }
        ?.toList()
    return CodexAccountUsageSnapshot(
        rateLimits = rateLimits,
        resetCredits = CodexRateLimitResetCredits(
            availableCount = (
                resetCredits?.availableCount
                    ?: usage.rateLimitResetCredits?.availableCount
                )?.coerceAtLeast(0L),
            credits = detailedCredits,
        ),
        tokenUsage = tokenUsage?.stats?.toDomain(),
        unavailableSections = unavailableSections,
        fetchedAt = fetchedAt,
    )
}

private fun io.github.stream29.kodex.openai.CodexRateLimitWindow.toDomain():
    CodexAccountRateLimitWindow =
    CodexAccountRateLimitWindow(
        usedPercent = usedPercent,
        durationSeconds = limitWindowSeconds,
        resetAfterSeconds = resetAfterSeconds,
        resetsAt = Instant.fromEpochSeconds(resetAt),
    )

private fun CodexTokenUsageProfileStats.toDomain(): CodexAccountTokenUsage =
    CodexAccountTokenUsage(
        lifetimeTokens = lifetimeTokens,
        peakDailyTokens = peakDailyTokens,
        longestRunningTurnSeconds = longestRunningTurnSeconds,
        currentStreakDays = currentStreakDays,
        longestStreakDays = longestStreakDays,
        dailyUsageBuckets = dailyUsageBuckets?.map { bucket ->
            CodexAccountTokenUsageDailyBucket(
                startDate = bucket.startDate,
                tokens = bucket.tokens,
            )
        },
    )

private suspend fun <T> optionalSection(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        Result.failure(failure)
    }

private fun CodexRateLimitResetConsumeCode.toOutcome(): CodexRateLimitResetOutcome =
    when (this) {
        CodexRateLimitResetConsumeCode.Reset -> CodexRateLimitResetOutcome.Reset
        CodexRateLimitResetConsumeCode.NothingToReset -> CodexRateLimitResetOutcome.NothingToReset
        CodexRateLimitResetConsumeCode.NoCredit -> CodexRateLimitResetOutcome.NoCredit
        CodexRateLimitResetConsumeCode.AlreadyRedeemed -> CodexRateLimitResetOutcome.AlreadyRedeemed
    }

private fun Throwable.usageFailureMessage(fallback: String): String =
    message
        ?.takeIf(String::isNotBlank)
        ?.let { detail -> "$fallback $detail" }
        ?: fallback

private fun String.toInstantOrNull(): Instant? =
    runCatching { Instant.parse(this) }.getOrNull()

private fun OpenAiSubscriptionAuthState.accountKey(): AccountKey =
    AccountKey(
        accountId = accountId?.takeIf(String::isNotBlank),
        tokenFallback = accessToken.takeIf {
            accountId.isNullOrBlank()
        },
    )

private fun KodexAuthState.matches(
    expectedAccount: OpenAiSubscriptionAuthState,
    expectedKey: AccountKey,
): Boolean =
    this is KodexAuthState.Authenticated &&
        value.accountKey() == expectedKey &&
        value.accessToken == expectedAccount.accessToken

private data class AccountKey(
    val accountId: String?,
    val tokenFallback: String?,
)

private const val CodexRateLimitResetType: String = "codex_rate_limits"
private const val AvailableResetCreditStatus: String = "available"
