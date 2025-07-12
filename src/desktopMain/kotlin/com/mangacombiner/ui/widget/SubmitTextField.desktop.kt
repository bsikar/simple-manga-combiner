package com.mangacombiner.ui.widget

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

@Composable
actual fun SubmitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    singleLine: Boolean,
    placeholder: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    keyboardOptions: KeyboardOptions
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        modifier = modifier.onKeyEvent {
            if (it.type == KeyEventType.KeyUp && it.key == Key.Enter && enabled) {
                onSubmit()
                true
            } else {
                false
            }
        },
        singleLine = singleLine,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        trailingIcon = trailingIcon
    )
}
