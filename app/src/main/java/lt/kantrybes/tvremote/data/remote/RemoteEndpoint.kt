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
        return trimmed
    }
}
