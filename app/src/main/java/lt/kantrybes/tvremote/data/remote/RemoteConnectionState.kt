package lt.kantrybes.tvremote.data.remote

internal sealed interface RemoteConnectionState {
    data object Disconnected : RemoteConnectionState
    data object Connecting : RemoteConnectionState
    data object Connected : RemoteConnectionState
    data object Reconnecting : RemoteConnectionState
    data class NeedsPairing(
        val rejected: Boolean = false,
        val certificateChanged: Boolean = false,
    ) : RemoteConnectionState
}
