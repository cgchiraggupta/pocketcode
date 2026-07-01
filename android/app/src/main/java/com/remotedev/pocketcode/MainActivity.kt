package com.remotedev.pocketcode

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import com.remotedev.pocketcode.ui.Root

class MainActivity : FragmentActivity() {
    private val openDiffFor = mutableStateOf<String?>(null)
    private val isAuthorized = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        checkBiometricsAndPrompt()
        setContent { App(isAuthorized.value, openDiffFor.value) { openDiffFor.value = it } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("openDiffFor")?.let { openDiffFor.value = it }
    }

    private fun checkBiometricsAndPrompt() {
        val manager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        when (manager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> showBiometricPrompt(authenticators)
            else -> isAuthorized.value = true
        }
    }

    private fun showBiometricPrompt(authenticators: Int) {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(applicationContext, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                finish()
            }
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                isAuthorized.value = true
            }
        }
        val prompt = BiometricPrompt(this, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock PocketCode")
            .setSubtitle("Authenticate to access your remote development machine")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }
}

@Composable
fun App(isAuthorized: Boolean, openDiffFor: String?, clearOpenDiffFor: (String?) -> Unit) {
    MaterialTheme {
        Surface {
            if (isAuthorized) {
                Root(openDiffFor, clearOpenDiffFor)
            } else {
                BoxPlaceholder()
            }
        }
    }
}

@Composable
fun BoxPlaceholder() {
    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.material3.Text("Authorization Required")
    }
}
