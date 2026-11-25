package app.locationjournal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun OnboardingScreen(viewModel: JournalViewModel, nav: NavController) {
    val requestLocation = rememberLocationPermissionRequest { granted ->
        if (granted) {
            viewModel.completeOnboarding()
            nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
        }
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Location journal", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "A private record of where you go, what you saw, and how long you stayed. Everything stays on this device.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What the app stores", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("• GPS points while you choose to track\n• Visits and places derived from those points\n• Photos you add and notes you write\n• No account, analytics, or upload")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Location is used only when tracking is on. A notification is shown while the service runs. Background location is optional and only needed if you want tracking while the screen is off.")
            }
        }
        Button(onClick = requestLocation, modifier = Modifier.fillMaxWidth()) { Text("Allow location and continue") }
        OutlinedButton(
            onClick = {
                viewModel.completeOnboarding()
                nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue without tracking")
        }
    }
}
