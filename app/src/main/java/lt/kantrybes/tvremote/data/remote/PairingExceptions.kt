package lt.kantrybes.tvremote.data.remote

internal class InvalidTvAddressException(val kind: Kind) : Exception() {
    enum class Kind { EMPTY, INVALID }
}

internal class CannotConnectException(
    val kind: Kind,
    cause: Throwable? = null,
) : Exception(cause) {
    enum class Kind { REFUSED, TIMEOUT, TLS, OTHER }
}

internal class InvalidPairingCodeException(val reason: Reason) : Exception() {
    enum class Reason { FORMAT, MISMATCH }
}

internal class PairingProtocolException(
    val kind: Kind,
    cause: Throwable? = null,
) : Exception(cause) {
    enum class Kind { BAD_SECRET, CLOSED, UNEXPECTED }
}
