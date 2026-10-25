package it.peppedess.dexreceiver

import java.util.concurrent.TimeUnit

/**
 * Minimal root-shell probe. Commands are fixed by the app; never pass user input here.
 * A successful result means su returned UID 0, not that Android signature permissions
 * or Wi-Fi Display framework capabilities have been granted.
 */
object RootAccess {
    data class Result(
        val output: String,
        val exitCode: Int,
        val timedOut: Boolean
    )

    fun run(command: String, timeoutSeconds: Long = 8): Result {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            Result(
                output = output.ifBlank { if (completed) "(nessun output)" else "(timeout)" },
                exitCode = if (completed) process.exitValue() else -1,
                timedOut = !completed
            )
        } catch (t: Throwable) {
            try {
                process?.destroyForcibly()
            } catch (_: Throwable) {
            }
            Result(
                output = "su non disponibile: ${t.javaClass.simpleName}: ${t.message ?: "errore"}",
                exitCode = -1,
                timedOut = false
            )
        }
    }
}
