package dev.firetvlite.iptv.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.ui.theme.FireLiteTheme

/** A labelled single-line text field. OK on the remote opens the on-screen keyboard. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
) {
    val colors = FireLiteTheme.colors
    var editing by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    var sawKeyboard by remember { mutableStateOf(false) }
    val editorFocus = remember { FocusRequester() }
    val wrapperFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    fun finishEditing() {
        keyboard?.hide()
        runCatching { wrapperFocus.requestFocus() }
        restoreFocus = true
        editing = false
        sawKeyboard = false
    }

    BackHandler(enabled = editing) { finishEditing() }
    LaunchedEffect(editing) {
        if (editing) {
            editorFocus.requestFocusWhenReady()
            keyboard?.show()
        } else if (restoreFocus) {
            wrapperFocus.requestFocusWhenReady()
            restoreFocus = false
        }
    }
    LaunchedEffect(editing, keyboardVisible) {
        if (editing) {
            if (keyboardVisible) sawKeyboard = true
            else if (sawKeyboard) finishEditing()
        }
    }
    Column(modifier = modifier) {
        FLText(
            text = label,
            style = FireLiteTheme.typography.label,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        // The surface stays composed while editing: swapping it out dropped focus to the first rail item,
        // which in any section but the first closed the field before the keyboard opened.
        FocusSurface(
            onClick = { editing = true },
            modifier = Modifier.fillMaxWidth().focusRequester(wrapperFocus)
                .border(2.dp, if (editing) colors.focusRing else Color.Transparent, FireLiteTheme.shapes.medium),
            background = SolidColor(colors.surfaceHigh),
            focusedScale = 1f,
        ) {
            if (editing) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = FireLiteTheme.typography.title,
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri, imeAction = ImeAction.Done),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardActions = KeyboardActions(onDone = { finishEditing() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(editorFocus)
                        .onPreInterceptKeyBeforeSoftKeyboard { event ->
                            if (event.key == Key.Back) {
                                if (event.type == KeyEventType.KeyUp) finishEditing()
                                true
                            } else false
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            } else {
                FLText(
                    text = (if (secret) "•".repeat(value.length) else value).ifEmpty { " " },
                    style = FireLiteTheme.typography.title,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }
}
