package com.genuine.app.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.genuine.app.data.ProfileRepo
import kotlinx.coroutines.launch

class VerificationViewModel : ViewModel() {
    var status by mutableStateOf("pending")
    var error by mutableStateOf<String?>(null)

    fun refresh(onVerified: () -> Unit) = viewModelScope.launch {
        runCatching { ProfileRepo.myProfile()?.verification ?: "pending" }
            .onSuccess { status = it; if (it == "verified") onVerified() }
    }

    fun start(open: (String) -> Unit) = viewModelScope.launch {
        error = null
        runCatching { ProfileRepo.startVerification() }
            .onSuccess { status = "in_review"; open(it) }
            .onFailure { error = it.message }
    }
}

@Composable
fun VerificationScreen(onVerified: () -> Unit, vm: VerificationViewModel = viewModel()) {
    val context = LocalContext.current
    // Re-check status whenever the user returns from the vendor's flow.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh(onVerified) }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Verify it's really you", style = MaterialTheme.typography.headlineMedium)
        Text("A quick live selfie and ID check. Only a \"Verified\" badge is shown on your profile. We don't store your ID document.")

        when (vm.status) {
            "in_review" -> {
                Text("Checking… this usually takes a minute or two.")
                OutlinedButton(onClick = { vm.refresh(onVerified) }) { Text("Refresh") }
            }
            "rejected" -> Text("We couldn't verify you. Please try again in good light with a valid ID.",
                color = MaterialTheme.colorScheme.error)
        }

        if (vm.status != "in_review") {
            Button(onClick = {
                vm.start { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }, modifier = Modifier.fillMaxWidth()) { Text("Start verification") }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
