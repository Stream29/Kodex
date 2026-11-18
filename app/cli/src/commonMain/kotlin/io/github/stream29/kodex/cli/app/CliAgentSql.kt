package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.utils.rpcexception.BackendLocalCredentialRequired
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun executeAgentSql(
    command: AgentSqlCommand, services: RpcServices, readStdin: () -> String, write: (String) -> Unit,
): Int {
    val sql = if (command.input == AgentSqlInput.Stdin) {
        try {
            readStdin().also {
                require(it.length <= AgentSqlInputBytes && it.isNotBlank() && '\u0000' !in it &&
                    it.encodeToByteArray().size <= AgentSqlInputBytes)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return writeAgentSqlError(AgentSqlError.INVALID_INPUT, "Expected a nonempty UTF-8 SQL statement up to 64 KiB.", write)
        }
    } else null
    val (body, status) = try {
        if (sql == null) {
            val schema = services.global.getAgentSqlSchema()
            AgentSqlResultJson.encodeToString(AgentSqlSchemaResult.serializer(), schema) to 0
        } else {
            val answer = services.global.queryAgentSql(sql)
            AgentSqlResultJson.encodeToString(AgentSqlQueryResult.serializer(), answer) to if (answer.error == null) 0 else 1
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: BackendLocalCredentialRequired) {
        return writeAgentSqlError(AgentSqlError.DENIED, "Agent SQL requires the local backend credential.", write)
    } catch (failure: Throwable) {
        if (failure is Error) throw failure
        currentCoroutineContext().ensureActive()
        return writeAgentSqlError(AgentSqlError.UNAVAILABLE, "The selected backend did not return an SQL result.", write)
    }
    write(body)
    return status
}

internal fun writeAgentSqlError(code: AgentSqlError, message: String, write: (String) -> Unit): Int {
    val result = AgentSqlQueryResult(0, error = AgentSqlDiagnostic(code, message))
    write(AgentSqlResultJson.encodeToString(AgentSqlQueryResult.serializer(), result))
    return 1
}
