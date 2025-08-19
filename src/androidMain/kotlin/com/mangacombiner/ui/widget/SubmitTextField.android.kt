package com.mangacombiner.ui.widget

import androidx.compose.foundation.text.KeyboardActions
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
    options: SubmitTextFieldOptions
) {
    val submit = { if (options.enabled) onSubmit() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = options.placeholder,
        modifier = options.modifier,
        singleLine = options.singleLine,
        enabled = options.enabled,
        keyboardOptions = options.keyboardOptions,
        keyboardActions = when (options.keyboardOptions.imeAction) {
            ImeAction.Done -> KeyboardActions(onDone = { submit() })
            ImeAction.Go -> KeyboardActions(onGo = { submit() })
            ImeAction.Search -> KeyboardActions(onSearch = { submit() })
            ImeAction.Send -> KeyboardActions(onSend = { submit() })
            else -> KeyboardActions.Default
        },
        trailingIcon = options.trailingIcon
    )
}
