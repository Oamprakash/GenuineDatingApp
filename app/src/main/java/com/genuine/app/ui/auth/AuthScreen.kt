package com.genuine.app.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.genuine.app.data.AuthRepo
import kotlinx.coroutines.launch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role

class AuthViewModel : ViewModel() {
    var phone by mutableStateOf("")
    var code by mutableStateOf("")
    var codeSent by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    var ageConfirmed by mutableStateOf(false)

    fun onPhoneChange(input: String) {
        var digits = input.filter(Char::isDigit)
        if (digits.length > 10 && digits.startsWith("91")) digits = digits.drop(2) // pasted +91
        if (digits.startsWith("0")) digits = digits.drop(1)                         // pasted 0-prefix
        phone = digits.take(10)
    }

    val phoneValid get() = phone.length == 10 && phone.first() in '6'..'9'

    private val e164 get() = "+91$phone"

    fun sendCode() = run { AuthRepo.sendOtp(e164); codeSent = true }
    fun verify(onSignedIn: () -> Unit) = run { AuthRepo.verifyOtp(e164, code); onSignedIn() }

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        loading = true; error = null
        runCatching { block() }.onFailure { error = it.message ?: "Something went wrong" }
        loading = false
    }
}

@Composable
fun AuthScreen(onSignedIn: () -> Unit, vm: AuthViewModel = viewModel()) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Genuine", style = MaterialTheme.typography.displaySmall)
        Text("Real people. Real meetups.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))

        if (!vm.codeSent) {
            OutlinedTextField(
                value = vm.phone,
                onValueChange = vm::onPhoneChange,
                label = { Text("Mobile number") },
                prefix = { Text("+91 ") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                isError = vm.phone.length == 10 && !vm.phoneValid,
                supportingText = {
                    if (vm.phone.length == 10 && !vm.phoneValid) Text("Enter a valid Indian mobile number")
                    else Text("${vm.phone.length}/10")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = vm.ageConfirmed, onValueChange = { vm.ageConfirmed = it }, role = Role.Checkbox)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = vm.ageConfirmed, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    "I'm 18 or older and agree to the Terms and Privacy Policy.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.sendCode() },
                enabled = !vm.loading && vm.phoneValid && vm.ageConfirmed,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send code") }
        } else {
            OutlinedTextField(
                value = vm.code, onValueChange = { vm.code = it.filter(Char::isDigit).take(6) },
                label = { Text("6-digit code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.verify(onSignedIn) },
                enabled = !vm.loading && vm.code.length == 6,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Verify") }
            TextButton(onClick = { vm.codeSent = false; vm.code = "" }) { Text("Change number") }
        }
        vm.error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
