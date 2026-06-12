package com.EdgeRip.KomsRooms

import com.EdgeRip.KomsRooms.model.PlayerState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * All network calls to the Pi's snapcastbt web UI (port 8080).
 * Everything goes through port 8080 — no direct port 3678 access needed.
 */
object PiApiClient {

    var piIp: String   = ""
    var webPort: Int   = 8080
    var snapPort: Int  = 1704

    private val webBase get() = "http://$piIp:$webPort"

    // ── Player state ─────────────────────────────────────────────────────────
    // Routes through webBase/api/player (web UI proxies to go-librespot).
    // This works from any network location — only port 8080 needs to be reachable.

    suspend fun getStatus(): PlayerState = withContext(Dispatchers.IO) {
        try {
            val json = get("$webBase/api/player") ?: return@withContext PlayerState(
                connected = false, error = "No response from server"
            )
            val data = JSONObject(json)
            if (!data.optBoolean("ok", false)) return@withContext PlayerState(
                connected = false, error = data.optString("error", "Server error")
            )

            PlayerState(
                title      = data.optString("title",    ""),
                artist     = data.optString("artist",   ""),
                album      = data.optString("album",    ""),
                artUrl     = data.optString("art_url",  "").ifEmpty { null },
                positionMs = data.optLong("position_ms", 0L),
                durationMs = data.optLong("duration_ms", 0L),
                playing    = data.optBoolean("playing",   false),
                hasTrack   = data.optBoolean("has_track", false),
                connected  = true,
                trackUri   = data.optString("track_uri", "").ifEmpty { null }
            )
        } catch (e: Exception) {
            PlayerState(connected = false, error = e.message)
        }
    }

    // ── Transport controls ────────────────────────────────────────────────────

    suspend fun playerCommand(cmd: String): Boolean = withContext(Dispatchers.IO) {
        post("$webBase/api/player/$cmd", "{}") != null
    }

    // ── EQ ───────────────────────────────────────────────────────────────────

    suspend fun setEq(clientId: String, bands: Map<String, Float>): Boolean =
        withContext(Dispatchers.IO) {
            val bandsJson = JSONObject().apply { bands.forEach { (k, v) -> put(k, v) } }
            val payload   = JSONObject().apply {
                put("client_id", clientId)
                put("bands", bandsJson)
            }
            post("$webBase/api/set_eq", payload.toString()) != null
        }

    // ── Snapcast server discovery via direct TCP (no mDNS) ───────────────────

    suspend fun scanForServers(
        subnet: String,
        rpcPort: Int = 1705,
        timeoutMs: Int = 500
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val found = mutableListOf<Pair<String, String>>()
        coroutineScope {
            (1..254).chunked(32).forEach { batch ->
                val jobs = batch.map { octet ->
                    async {
                        val ip = "$subnet.$octet"
                        val name = snapcastServerName(ip, rpcPort, timeoutMs)
                        if (name != null) synchronized(found) { found.add(ip to name) }
                    }
                }
                jobs.forEach { it.await() }
            }
        }
        found.sortedBy { it.first }
    }

    /**
     * Confirms a host is a KomsRoom server (port 1705 responds to Snapcast RPC),
     * then fetches the friendly name from /api/info on port 8080.
     * Falls back to the Snapcast server hostname if /api/info is unavailable.
     */
    suspend fun snapcastServerName(
        ip: String,
        rpcPort: Int = 1705,
        timeoutMs: Int = 500
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Step 1: quick check — confirm Snapcast RPC responds (fast, uses timeoutMs)
            val rpc = """{"jsonrpc":"2.0","id":1,"method":"Server.GetStatus","params":{}}""" + "\n"
            val rpcResponse = StringBuilder()
            Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress(ip, rpcPort), timeoutMs)
                sock.soTimeout = timeoutMs
                sock.getOutputStream().write(rpc.toByteArray())
                val line = sock.getInputStream().bufferedReader().readLine()
                    ?: return@withContext null
                rpcResponse.append(line)
            }

            // Step 2: fetch friendly name from /api/info (longer timeout — Python web server)
            try {
                val conn = URL("http://$ip:8080/api/info").openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout    = 1500
                if (conn.responseCode == 200) {
                    val name = JSONObject(conn.inputStream.bufferedReader().readText())
                        .optString("server_name", "").trim()
                    if (name.isNotEmpty()) return@withContext name
                }
            } catch (_: Exception) { }

            // Step 3: fall back to hostname from RPC response
            // Snapcast Server.GetStatus: result.server.host.name
            val json   = JSONObject(rpcResponse.toString())
            val result = json.optJSONObject("result") ?: return@withContext ip
            val server = result.optJSONObject("server") ?: return@withContext ip
            val host   = server.optJSONObject("host")  ?: return@withContext ip
            host.optString("name", ip).ifEmpty { ip }
        } catch (_: Exception) {
            null
        }
    }

    // ── Volume / mute via Snapcast JSON-RPC (port 1705) ──────────────────────

    suspend fun setVolume(clientId: String, percent: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket(piIp, 1705).use { sock ->
                    val rpc = """{"jsonrpc":"2.0","id":1,"method":"Client.SetVolume",""" +
                              """"params":{"id":"$clientId","volume":{"percent":$percent,"muted":false}}}""" + "\n"
                    sock.getOutputStream().write(rpc.toByteArray())
                }
                true
            } catch (e: Exception) { false }
        }

    /**
     * Mute or unmute the Snapcast client whose host IP matches [deviceIp].
     * Falls back to the first connected client if no IP match is found.
     * Groups live at result.groups (NOT result.server.groups).
     */
    suspend fun muteClient(deviceIp: String, muted: Boolean, rpcPort: Int = 1705): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val rpc = """{"jsonrpc":"2.0","id":1,"method":"Server.GetStatus","params":{}}""" + "\n"
                val line = Socket().use { sock ->
                    sock.connect(java.net.InetSocketAddress(piIp, rpcPort), 2000)
                    sock.soTimeout = 2000
                    sock.getOutputStream().write(rpc.toByteArray())
                    sock.getInputStream().bufferedReader().readLine()
                } ?: return@withContext false

                // groups are at result.server.groups
                val groups = JSONObject(line)
                    .optJSONObject("result")
                    ?.optJSONObject("server")
                    ?.optJSONArray("groups") ?: return@withContext false

                // Collect all clients; prefer IP match, fall back to first connected.
                // Snapcast stores IPs as IPv6-mapped (::ffff:x.x.x.x) — strip prefix before comparing.
                data class ClientEntry(val id: String, val percent: Int, val ipMatch: Boolean)
                val candidates = mutableListOf<ClientEntry>()

                for (i in 0 until groups.length()) {
                    val clients = groups.getJSONObject(i).optJSONArray("clients") ?: continue
                    for (j in 0 until clients.length()) {
                        val client  = clients.getJSONObject(j)
                        if (!client.optBoolean("connected", true)) continue
                        val rawIp   = client.optJSONObject("host")?.optString("ip", "") ?: ""
                        val hostIp  = rawIp.removePrefix("::ffff:")
                        val id      = client.optString("id", "")
                        val percent = client.optJSONObject("config")
                            ?.optJSONObject("volume")?.optInt("percent", 100) ?: 100
                        candidates.add(ClientEntry(id, percent, hostIp == deviceIp))
                    }
                }

                val target = candidates.firstOrNull { it.ipMatch } ?: candidates.firstOrNull()
                    ?: return@withContext false

                Socket(piIp, rpcPort).use { sock ->
                    val cmd = """{"jsonrpc":"2.0","id":2,"method":"Client.SetVolume",""" +
                              """"params":{"id":"${target.id}","volume":{"percent":${target.percent},"muted":$muted}}}""" + "\n"
                    sock.getOutputStream().write(cmd.toByteArray())
                }
                true
            } catch (e: Exception) { false }
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun get(url: String, timeoutMs: Int = 4000): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout    = timeoutMs
        conn.requestMethod  = "GET"
        if (conn.responseCode == 200) {
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            body
        } else {
            conn.disconnect(); null
        }
    } catch (e: Exception) { null }

    private fun post(url: String, body: String, timeoutMs: Int = 4000): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout    = timeoutMs
        conn.requestMethod  = "POST"
        conn.doOutput       = true
        conn.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        val code = conn.responseCode
        val resp = if (code < 400) conn.inputStream.bufferedReader().readText()
                   else conn.errorStream?.bufferedReader()?.readText()
        conn.disconnect()
        if (code in 200..299) resp else null
    } catch (e: Exception) { null }
}
