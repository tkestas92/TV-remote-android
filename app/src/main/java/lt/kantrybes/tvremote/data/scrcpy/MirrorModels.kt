package lt.kantrybes.tvremote.data.scrcpy

internal sealed interface MirrorState {
    data object Idle : MirrorState
    data object Connecting : MirrorState
    data object StartingServer : MirrorState
    data class Streaming(val width: Int, val height: Int) : MirrorState
    data class Failed(val message: String) : MirrorState
}

internal sealed interface MirrorStage {
    data object Idle : MirrorStage
    data object PushingJar : MirrorStage
    data object StartingServer : MirrorStage
    data object ConnectingVideo : MirrorStage
    data object WaitingFirstFrame : MirrorStage
    data class Streaming(val width: Int, val height: Int) : MirrorStage
    data class Failed(val message: String) : MirrorStage
}
