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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import com.remotedev.pocketcode.ui.Root

// Claude-branded dark palette
private val ClaudeColors = darkColorScheme(
    primary            = Color(0xFFD97757), // Claude orange
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFF7C3AED), // deep purple
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary          = Color(0xFF9D7CF4), // medium purple
    onSecondary        = Color(0xFF1A0A33),
    secondaryContainer = Color(0xFF2D1B69),
    onSecondaryContainer = Color(0xFFDDD6FE),
    tertiary           = Color(0xFF60A5FA),
    background         = Color(0xFF0F0F10),
    onBackground       = Color(0xFFE8E3DD),
    surface            = Color(0xFF1A1A1C),
    onSurface          = Color(0xFFE8E3DD),
    surfaceVariant     = Color(0xFF252528),
    onSurfaceVariant   = Color(0xFFB5B0AB),
    outline            = Color(0xFF3A3A3F),
    error              = Color(0xFFEF4444),
    onError            = Color(0xFFFFFFFF),
)

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
    MaterialTheme(colorScheme = ClaudeColors) {
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
