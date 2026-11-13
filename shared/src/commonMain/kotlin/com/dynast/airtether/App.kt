package com.dynast.airtether

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.dynast.airtether.ui.TetheringScreen
import com.dynast.airtether.ui.theme.AirTetherTheme

@Composable
@Preview
fun App() {
    AirTetherTheme {
        TetheringScreen()
    }
}
