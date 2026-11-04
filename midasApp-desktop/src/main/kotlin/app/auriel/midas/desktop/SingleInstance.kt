package app.auriel.midas.desktop

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * One Midas window: a second launch ("Open with…" on another file) hands its arguments to the running
 * instance over a loopback socket, then exits. The port lives in the config folder.
 */
object SingleInstance {
    private val portFile = File(AppDirs.config, "instance.port")
    private const val HELLO = "midas-open-v1"

    /** True when another instance took the arguments (this process should exit). */
    fun forwardToRunning(args: List<String>): Boolean {
        val port = portFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: return false
        return runCatching {
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.soTimeout = 2_000
                val out = s.getOutputStream().bufferedWriter(Charsets.UTF_8)
                out.write(HELLO + "\n")
                args.forEach { out.write(it.replace("\n", " ") + "\n") }
                out.write("\n")
                out.flush()
                s.getInputStream().bufferedReader().readLine() == "ok"
            }
        }.getOrDefault(false)
    }

    /** Listens for later launches; [onArgs] runs on a background thread. */
    fun listen(onArgs: (List<String>) -> Unit) {
        val server = runCatching { ServerSocket(0, 10, InetAddress.getLoopbackAddress()) }.getOrNull() ?: return
        portFile.writeText(server.localPort.toString())
        Runtime.getRuntime().addShutdownHook(Thread { if (portFile.readText().trim() == server.localPort.toString()) portFile.delete() })
        Thread({
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { s ->
                        s.soTimeout = 2_000
                        val reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
                        if (reader.readLine() != HELLO) return@use
                        val args = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        s.getOutputStream().write("ok\n".toByteArray())
                        onArgs(args)
                    }
                }
            }
        }, "midas-single-instance").apply { isDaemon = true; start() }
    }
}
