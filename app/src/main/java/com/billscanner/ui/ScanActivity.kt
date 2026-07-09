package com.billscanner.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.billscanner.ui.composable.ScanScreen
import com.billscanner.ui.viewmodel.ScanViewModel

class ScanActivity : ComponentActivity() {

    private lateinit var viewModel: ScanViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[ScanViewModel::class.java]

        setContent {
            MaterialTheme {
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
