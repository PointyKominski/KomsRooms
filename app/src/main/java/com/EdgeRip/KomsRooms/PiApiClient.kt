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
 * All HTTP calls to the Pi's snapcastbt web UI and go-librespot HTTP API.
 * Uses only Android's built-in HttpURLConnection — no external HTTP library needed.
 */
object PiApiClient {

    var piIp: String   = ""
    var webPort: Int   = 8080
    var snapPort: Int  = 1704

    private val librespotBase get() = "http://$piIp:3678"
    private val webBase       get() = "http://$piIp:$webPort"

    // ── Player state ─────────────────────────────────────────────────────────

    suspend fun getStatus(): PlayerState = withContext(Dispatchers.IO) {
        try {
            val json = get("$librespotBase/status") ?: return@withContext PlayerState(
                connected = false, error = "No response from go-librespot"
            )
            val data  = JSONObject(json)
            val track = data.optJSONObject("track")

            val title = track?.optString("name", "") ?: ""

            val artistNames = track?.optJSONArray("artist_names")
            val artist = buildString {
                if (artistNames != null) {
                    for (i in 0 until artistNames.length()) {
                        if (i > 0) append(", ")
                        append(artistNames.getString(i))
                    }
                }
            }

            val album   = track?.optString("album_name", "") ?: ""
            val artUrl  = track?.optString("album_cover_url")?.ifEmpty { null }
            val posMs   = track?.optLong("position", 0L) ?: 0L
            val durMs   = track?.optLong("duration",  0L) ?: 0L
            val paused  = data.optBoolean("paused",  false)
            val stopped = data.optBoolean("stopped", false)

            PlayerState(
                title      = title,
                artist     = artist,
                album      = album,
                artUrl     = artUrl,
                positionMs = posMs,
                durationMs = durMs,
                playing    = !paused && !stopped,
                hasTrack   = title.isNotEmpty(),
                connected  = true
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

    /**
     * Scans the given subnet (e.g. "192.168.1") for hosts responding to
     * the Snapcast JSON-RPC on port 1705. For each host found, fetches
     * the server name via Server.GetStatus.
     * Returns a list of (ip, serverName) pairs.
     */
    suspend fun scanForServers(
        subnet: String,          // e.g. "192.168.1"
        rpcPort: Int = 1705,
        timeoutMs: Int = 500
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val found = mutableListOf<Pair<String, String>>()
        // Scan in batches of 32 to avoid overwhelming the device's network stack
        // with 254 simultaneous TCP connections
        kotlinx.coroutines.coroutineScope {
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
     * Tries to connect to a server on port 1705 (audio server RPC).
     * If confirmed, queries /api/info on port 8080 for the friendly server name.
     * Falls back to hostname from RPC if /api/info is unavailable.
     * Returns the display name, or null if not an audio server.
     */
    suspend fun snapcastServerName(
        ip: String,
        rpcPort: Int = 1705,
        timeoutMs: Int = 500
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Step 1: confirm this is an audio server via RPC
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

            // Step 2: try /api/info on port 8080 for the friendly server name
            try {
                val url = java.net.URL("http://$ip:8080/api/info")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout    = timeoutMs
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val info = JSONObject(body)
                    val name = info.optString("server_name", "").trim()
                    if (name.isNotEmpty()) return@withContext name
                }
            } catch (_: Exception) { /* fall through to RPC hostname */ }

            // Step 3: fall back to hostname from RPC response
            val json   = JSONObject(rpcResponse.toString())
            val result = json.optJSONObject("result") ?: return@withContext ip
            val server = result.optJSONObject("server") ?: return@withContext ip
            val host   = server.optJSONObject("host")  ?: return@withContext ip
            host.optString("name", ip).ifEmpty { ip }
        } catch (e: Exception) {
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
     * Looks up the client ID from Server.GetStatus, preserves current volume %.
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

                val groups = JSONObject(line)
                    .optJSONObject("result")
                    ?.optJSONObject("server")
                    ?.optJSONArray("groups") ?: return@withContext false

                for (i in 0 until groups.length()) {
                    val clients = groups.getJSONObject(i).optJSONArray("clients") ?: continue
                    for (j in 0 until clients.length()) {
                        val client  = clients.getJSONObject(j)
                        val hostIp  = client.optJSONObject("host")?.optString("ip", "") ?: ""
                        if (hostIp != deviceIp) continue
                        val id      = client.optString("id", "")
                        val percent = client.optJSONObject("config")
                            ?.optJSONObject("volume")?.optInt("percent", 100) ?: 100
                        Socket(piIp, rpcPort).use { sock ->
                            val cmd = """{"jsonrpc":"2.0","id":2,"method":"Client.SetVolume",""" +
                                      """"params":{"id":"$id","volume":{"percent":$percent,"muted":$muted}}}""" + "\n"
                            sock.getOutputStream().write(cmd.toByteArray())
                        }
                        return@withContext true
                    }
                }
                false
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
