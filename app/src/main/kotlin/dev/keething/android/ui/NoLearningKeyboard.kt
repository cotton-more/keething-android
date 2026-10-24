package dev.keething.android.ui

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Text fields in [content] ask the keyboard not to learn what is typed (`IME_FLAG_NO_PERSONALIZED_LEARNING`;
 * Gboard's incognito mode): entries' names, usernames and notes stay out of its dictionary and suggestions.
 * Compose's keyboard options cannot say so, hence the interception (the documented way).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NoLearningKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            val private = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    return connection
                }
            }
            next.startInputMethod(private)
        },
        content = content,
    )
}
