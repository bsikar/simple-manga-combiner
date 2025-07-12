package com.mangacombiner.ui.widget

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction

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
    val submit = { if (enabled) onSubmit() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        modifier = modifier,
        singleLine = singleLine,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        keyboardActions = when (keyboardOptions.imeAction) {
            ImeAction.Done -> KeyboardActions(onDone = { submit() })
            ImeAction.Go -> KeyboardActions(onGo = { submit() })
            ImeAction.Search -> KeyboardActions(onSearch = { submit() })
            ImeAction.Send -> KeyboardActions(onSend = { submit() })
            else -> KeyboardActions.Default
        },
        trailingIcon = trailingIcon
    )
}
