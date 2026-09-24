package lt.kantrybes.tvremote.ui.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.data.remote.RemoteConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyboardSheet(
    viewModel: RemoteViewModel,
    onDismiss: () -> Unit,
) {
    val hint by viewModel.keyboardHint.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        viewModel.beginKeyboard()
        focus.requestFocus()
    }
    LaunchedEffect(connection) {
        if (connection is RemoteConnectionState.Connected && draft.isNotEmpty()) {
            viewModel.syncKeyboard(draft)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .imePadding(),
        ) {
            TextField(
                value = draft,
                onValueChange = { updated ->
                    draft = updated
                    viewModel.syncKeyboard(updated)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                singleLine = true,
                label = { Text(stringResource(R.string.keyboard_label)) },
                supportingText = if (hint) {
                    { Text(stringResource(R.string.keyboard_ime_hint)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.sendEnter() }),
            )
        }
    }
}
