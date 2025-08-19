package com.mangacombiner.ui.widget

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

data class SubmitTextFieldOptions(
    val enabled: Boolean = true,
    val singleLine: Boolean = true,
    val placeholder: @Composable (() -> Unit)? = null,
    val trailingIcon: @Composable (() -> Unit)? = null,
    val keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    val modifier: Modifier = Modifier
)

@Composable
expect fun SubmitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    onSubmit: () -> Unit,
    options: SubmitTextFieldOptions = SubmitTextFieldOptions()
)
