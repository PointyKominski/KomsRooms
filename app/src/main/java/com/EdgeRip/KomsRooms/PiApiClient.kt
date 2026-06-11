package com.EdgeRip.KomsRooms

import com.EdgeRip.KomsRooms.model.PlayerState
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
    var webPort: Int   = 5900
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
        timeoutMs: Int = 300     // short timeout per host — we try 254 IPs in parallel
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val found = mutableListOf<Pair<String, String>>()
        val jobs  = (1..254).map { octet ->
            kotlinx.coroutines.async {
                val ip = "$subnet.$octet"
                val name = snapcastServerName(ip, rpcPort, timeoutMs)
                if (name != null) synchronized(found) { found.add(ip to name) }
            }
        }
        jobs.forEach { it.await() }
        found.sortedBy { it.first }
    }

    /**
     * Tries to connect to a Snapcast RPC port and fetch the server name.
     * Returns the server name string, or null if not a Snapcast server.
     */
    suspend fun snapcastServerName(
        ip: String,
        rpcPort: Int = 1705,
        timeoutMs: Int = 500
    ): String? = withContext(Dispatchers.IO) {
        try {
            val rpc = """{"jsonrpc":"2.0","id":1,"method":"Server.GetStatus","params":{}}""" + "\n"
            val response = StringBuilder()
            Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress(ip, rpcPort), timeoutMs)
                sock.soTimeout = timeoutMs
                sock.getOutputStream().write(rpc.toByteArray())
                val reader = sock.getInputStream().bufferedReader()
                val line = reader.readLine() ?: return@withContext null
                response.append(line)
            }
            // Parse server name from response
            val json   = JSONObject(response.toString())
            val result = json.optJSONObject("result") ?: return@withContext null
            val server = result.optJSONObject("server") ?: return@withContext null
            val host   = server.optJSONObject("host")  ?: return@withContext null
            host.optString("name", ip).ifEmpty { ip }
        } catch (e: Exception) {
            null
        }
    }

    // ── Volume via Snapcast JSON-RPC (port 1705) ──────────────────────────────

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
