package lt.kantrybes.tvremote.data.remote

internal const val PAIRING_PORT = 6467

internal const val COMMAND_PORT = 6466

internal const val CLIENT_NAME = "TV Remote"

internal const val SERVICE_NAME = "atvremote"

internal const val CERT_COMMON_NAME = "tvremote"

internal object TvAddress {
    private val IPV4 = Regex(
        "^(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$",
    )

    fun requireIpv4(host: String): String {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) {
            throw InvalidTvAddressException(InvalidTvAddressException.Kind.EMPTY)
        }
        if (!IPV4.matches(trimmed)) {
            throw InvalidTvAddressException(InvalidTvAddressException.Kind.INVALID)
        }
        if (!isPrivateLan(trimmed)) {
            throw InvalidTvAddressException(InvalidTvAddressException.Kind.NOT_PRIVATE)
        }
        return trimmed
    }

    /** 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, and 169.254.0.0/16. */
    private fun isPrivateLan(host: String): Boolean {
        val octets = host.split('.').map { it.toInt() }
        if (octets.size != 4) return false
        val first = octets[0]
        val second = octets[1]
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 169 && second == 254)
    }
}
