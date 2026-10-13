package com.genuine.app.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.genuine.app.data.ProfileRepo
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class CameraViewModel : ViewModel() {
    /** True from the tap until the upload finishes, so a second tap is ignored. */
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var finished = false

    /** Returns false if a capture is already running or this screen is done. */
    fun startCapture(): Boolean {
        if (busy || finished) return false
        busy = true
        error = null
        return true
    }

    fun captureFailed(message: String?) {
        busy = false
        error = message ?: "Couldn't take the photo. Please try again."
    }

    fun upload(jpeg: ByteArray, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { ProfileRepo.uploadPhoto(jpeg) }
            .onSuccess {
                finished = true
                onDone() // runs once at most
            }
            .onFailure {
                busy = false
                error = it.message
            }
    }
}

/**
 * Profile photos can only be taken live with the front camera. There is no gallery picker,
 * so stolen or old photos can't be uploaded. The liveness vendor then face-matches this photo.
 */
@Composable
fun CameraCaptureScreen(onDone: () -> Unit, vm: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Take your profile photo", style = MaterialTheme.typography.headlineSmall)
        Text("Face the camera in good light. No filters, sunglasses or group photos.")
        Spacer(Modifier.height(16.dp))

        if (granted) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    PreviewView(ctx).also { view ->
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture)
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
            )
        } else {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("Camera permission is required to create a verified profile.")
            }
        }

        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = granted && !vm.busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (!vm.startCapture()) return@Button
                imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val rotation = image.imageInfo.rotationDegrees
                        val bitmap = image.toBitmap()
                        image.close()
                        vm.upload(bitmap.rotateAndCompress(rotation), onDone)
                    }
                    override fun onError(e: ImageCaptureException) { vm.captureFailed(e.message) }
                })
            },
        ) { Text(if (vm.busy) "Saving…" else "Capture") }
    }
}

private fun Bitmap.rotateAndCompress(degrees: Int): ByteArray {
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    val out = Bitmap.createBitmap(this, 0, 0, width, height, m, true)
    return ByteArrayOutputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }
}
