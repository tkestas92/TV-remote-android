package lt.kantrybes.tvremote.data.launcher

import lt.kantrybes.tvremote.BuildConfig
import lt.kantrybes.tvremote.data.remote.InvalidTvAddressException
import lt.kantrybes.tvremote.data.remote.TvAddress
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

internal const val LAUNCHER_PORT = 8765

internal val LAUNCHER_WALLPAPERS = listOf(
    "night",
    "aurora",
    "sunset",
    "forest",
    "ocean",
    "graphite",
)

internal data class LauncherApp(
    val packageName: String,
    val label: String,
)

internal data class LauncherSnapshot(
    val apps: List<LauncherApp>,
    val favorites: List<String>,
    val hidden: List<String>,
    val wallpaper: String,
)

internal class LauncherCallException(val kind: Kind) : Exception() {
    enum class Kind {
        UNREACHABLE,
        NOT_HOME,
        WRONG_PIN,
        UNAUTHORIZED,
        BAD_WALLPAPER,
        FAILED,
    }
}

internal class LauncherClient {
    fun pair(host: String, pin: String): String {
        val body = JSONObject().put("pin", pin).toString()
        val response = exchange(
            host = host,
            method = "POST",
            path = "/pair",
            token = null,
            body = body,
            accept = JSON,
            pairing = true,
            wallpaper = false,
        )
        return try {
            val token = JSONObject(response).optString("token").trim()
            if (token.isEmpty() || token.length > 512 || token.any { it.isWhitespace() }) {
                throw LauncherCallException(LauncherCallException.Kind.NOT_HOME)
            }
            token
        } catch (error: JSONException) {
            throw LauncherCallException(LauncherCallException.Kind.NOT_HOME)
        }
    }

    fun state(host: String, token: String): LauncherSnapshot {
        val response = exchange(
            host = host,
            method = "GET",
            path = "/state",
            token = token,
            body = null,
            accept = JSON,
            pairing = false,
            wallpaper = false,
        )
        return try {
            parseState(response)
        } catch (error: JSONException) {
            throw LauncherCallException(LauncherCallException.Kind.NOT_HOME)
        }
    }

    fun icon(host: String, token: String, packageName: String): ByteArray {
        if (!isLauncherPackage(packageName)) {
            throw LauncherCallException(LauncherCallException.Kind.FAILED)
        }
        return exchangeBytes(
            host = host,
            path = "/icon/$packageName",
            token = token,
        )
    }

    fun putFavorites(host: String, token: String, packages: List<String>) {
        exchange(
            host = host,
            method = "PUT",
            path = "/favorites",
            token = token,
            body = packagesBody(packages),
            accept = JSON,
            pairing = false,
            wallpaper = false,
        )
    }

    fun putHidden(host: String, token: String, packages: List<String>) {
        exchange(
            host = host,
            method = "PUT",
            path = "/hidden",
            token = token,
            body = packagesBody(packages),
            accept = JSON,
            pairing = false,
            wallpaper = false,
        )
    }

    fun putWallpaper(host: String, token: String, id: String) {
        if (id !in LAUNCHER_WALLPAPERS) {
            throw LauncherCallException(LauncherCallException.Kind.BAD_WALLPAPER)
        }
        exchange(
            host = host,
            method = "PUT",
            path = "/wallpaper",
            token = token,
            body = JSONObject().put("id", id).toString(),
            accept = JSON,
            pairing = false,
            wallpaper = true,
        )
    }

    fun launch(host: String, token: String, packageName: String) {
        if (!isLauncherPackage(packageName)) {
            throw LauncherCallException(LauncherCallException.Kind.FAILED)
        }
        exchange(
            host = host,
            method = "POST",
            path = "/launch",
            token = token,
            body = JSONObject().put("package", packageName).toString(),
            accept = JSON,
            pairing = false,
            wallpaper = false,
        )
    }

    private fun packagesBody(packages: List<String>): String {
        val array = JSONArray()
        packages.filter(::isLauncherPackage).forEach { array.put(it) }
        return JSONObject().put("packages", array).toString()
    }

    private fun exchangeBytes(host: String, path: String, token: String): ByteArray {
        return withConnection(host, "GET", path, token, body = null, accept = "image/png") { connection ->
            val code = connection.responseCode
            if (code == 401) throw LauncherCallException(LauncherCallException.Kind.UNAUTHORIZED)
            if (code !in 200..299) throw LauncherCallException(LauncherCallException.Kind.NOT_HOME)
            val stream = connection.inputStream ?: throw LauncherCallException(LauncherCallException.Kind.NOT_HOME)
            stream.use { readLimited(it, ICON_LIMIT) }
        }
    }

    private fun exchange(
        host: String,
        method: String,
        path: String,
        token: String?,
        body: String?,
        accept: String,
        pairing: Boolean,
        wallpaper: Boolean,
    ): String {
        return withConnection(host, method, path, token, body, accept) { connection ->
            readResponse(connection, pairing, wallpaper)
        }
    }

    private inline fun <T> withConnection(
        host: String,
        method: String,
        path: String,
        token: String?,
        body: String?,
        accept: String,
        block: (HttpURLConnection) -> T,
    ): T {
        if (BuildConfig.IS_DEMO) throw LauncherCallException(LauncherCallException.Kind.UNREACHABLE)
        val ip = try {
            TvAddress.requireIpv4(host)
        } catch (error: InvalidTvAddressException) {
            throw LauncherCallException(LauncherCallException.Kind.UNREACHABLE)
        }
        val connection = URL("http://$ip:$LAUNCHER_PORT$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", accept)
            if (!token.isNullOrEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            return block(connection)
        } catch (error: LauncherCallException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            throw mapIo(error)
        } catch (error: Exception) {
            throw LauncherCallException(LauncherCallException.Kind.UNREACHABLE)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection, pairing: Boolean, wallpaper: Boolean): String {
        val code = connection.responseCode
        if (code in 200..299) {
            val stream = try {
                connection.inputStream
            } catch (error: IOException) {
                null
            }
            return stream?.use { readLimited(it, JSON_LIMIT).toString(Charsets.UTF_8) }.orEmpty()
        }
        val errorStream = connection.errorStream
        val body = errorStream?.use { readLimited(it, JSON_LIMIT).toString(Charsets.UTF_8) }.orEmpty()
        throw when (code) {
            401 -> LauncherCallException(
                if (pairing) LauncherCallException.Kind.WRONG_PIN else LauncherCallException.Kind.UNAUTHORIZED,
            )
            403 -> LauncherCallException(
                if (pairing) LauncherCallException.Kind.WRONG_PIN else LauncherCallException.Kind.NOT_HOME,
            )
            400 -> LauncherCallException(
                when {
                    wallpaper -> LauncherCallException.Kind.BAD_WALLPAPER
                    pairing -> LauncherCallException.Kind.WRONG_PIN
                    else -> LauncherCallException.Kind.FAILED
                },
            )
            404, 409, 500, 502, 503 -> LauncherCallException(LauncherCallException.Kind.NOT_HOME)
            else -> LauncherCallException(
                if (body.isBlank()) LauncherCallException.Kind.FAILED else LauncherCallException.Kind.NOT_HOME,
            )
        }
    }

    private fun mapIo(error: IOException): LauncherCallException {
        val message = error.message.orEmpty()
        val unreachable = error is SocketTimeoutException ||
            error is UnknownHostException ||
            error is NoRouteToHostException ||
            message.contains("EHOSTUNREACH", ignoreCase = true) ||
            message.contains("ENETUNREACH", ignoreCase = true) ||
            message.contains("No route to host", ignoreCase = true) ||
            message.contains("Network is unreachable", ignoreCase = true) ||
            message.contains("ECONNABORTED", ignoreCase = true)
        if (unreachable) return LauncherCallException(LauncherCallException.Kind.UNREACHABLE)
        return LauncherCallException(LauncherCallException.Kind.NOT_HOME)
    }

    private fun readLimited(stream: InputStream, max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) throw IOException("response too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val JSON = "application/json"
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 5_000
        const val JSON_LIMIT = 512 * 1024
        const val ICON_LIMIT = 256 * 1024
    }
}

internal fun parseState(json: String): LauncherSnapshot {
    val root = JSONObject(json)
    val appsJson = root.optJSONArray("apps") ?: JSONArray()
    val apps = buildList {
        for (index in 0 until appsJson.length()) {
            val item = appsJson.optJSONObject(index) ?: continue
            val packageName = item.optString("package")
            if (!isLauncherPackage(packageName)) continue
            val label = item.optString("label").ifBlank { packageName }
            add(LauncherApp(packageName, label))
        }
    }
    return LauncherSnapshot(
        apps = apps,
        favorites = stringList(root.optJSONArray("favorites")),
        hidden = stringList(root.optJSONArray("hidden")),
        wallpaper = root.optString("wallpaper"),
    )
}

internal fun isLauncherPackage(value: String): Boolean {
    if (value.length !in 1..200) return false
    return value.matches(PACKAGE_NAME)
}

private fun stringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index)
            if (isLauncherPackage(value) && value !in this) add(value)
        }
    }
}

private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$")
