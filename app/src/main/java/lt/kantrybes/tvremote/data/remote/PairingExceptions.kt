package lt.kantrybes.tvremote.data.remote

internal class InvalidTvAddressException(val kind: Kind) : Exception() {
    enum class Kind { EMPTY, INVALID, NOT_PRIVATE }
}

/** Thrown from the TLS trust manager when the TV certificate does not match the pin. */
internal class TvCertificateChangedException : java.security.cert.CertificateException(
    "TV certificate changed",
)

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
