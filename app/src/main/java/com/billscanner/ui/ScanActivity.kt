package com.billscanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.billscanner.CrashLogger
import com.billscanner.ui.composable.ScanScreen
import com.billscanner.ui.viewmodel.ScanViewModel

class ScanActivity : ComponentActivity() {

    private lateinit var viewModel: ScanViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[ScanViewModel::class.java]

        val lastCrash = CrashLogger.readLog(this)

        setContent {
            MaterialTheme {
                var showCrashLog by remember { mutableStateOf(lastCrash != null) }

                if (showCrashLog && lastCrash != null) {
                    CrashLogDialog(
                        log = lastCrash,
                        onDismiss = {
                            showCrashLog = false
                            CrashLogger.clearLog(this@ScanActivity)
                        }
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScanScreen(
                        viewModel = viewModel,
                        onNavigateToReview = {
                            startActivity(android.content.Intent(this, ReviewActivity::class.java))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun CrashLogDialog(log: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crash Log") },
        text = {
            Column {
                Text(
                    "The app crashed. Please share this log:",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    Text(
                        text = log,
                        modifier = Modifier
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState()),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val clip = ClipData.newPlainText("crash", log)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    )
}
