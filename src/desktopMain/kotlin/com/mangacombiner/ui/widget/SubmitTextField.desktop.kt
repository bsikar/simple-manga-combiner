package com.mangacombiner.ui.widget

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
    options: SubmitTextFieldOptions
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = options.placeholder,
        modifier = options.modifier.onKeyEvent {
            if (it.type == KeyEventType.KeyUp && it.key == Key.Enter && options.enabled) {
                onSubmit()
                true
            } else {
                false
            }
        },
        singleLine = options.singleLine,
        enabled = options.enabled,
        keyboardOptions = options.keyboardOptions,
        trailingIcon = options.trailingIcon
    )
}
