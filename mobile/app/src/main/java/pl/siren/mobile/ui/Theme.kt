package pl.siren.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object SirenColors {
    val Navy = Color(0xFF0F1724)
    val Ink = Color(0xFF1B2230)
    val Muted = Color(0xFF5B6575)
    val Soft = Color(0xFFF2F4F7)
    val Line = Color(0xFFE3E6EA)
    val Red = Color(0xFFD7262E)
    val RedSoft = Color(0xFFFDECEC)
    val Green = Color(0xFF2E9E5B)
    val GreenSoft = Color(0xFFDDF3E6)
    val Amber = Color(0xFFB26A00)
    val AmberSoft = Color(0xFFFDEBC8)
    val Blue = Color(0xFF1F6FD1)
    val BlueSoft = Color(0xFFE0ECFB)
}

@Composable
fun SirenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = SirenColors.Navy,
            onPrimary = Color.White,
            secondary = SirenColors.Red,
            error = SirenColors.Red,
            background = Color.White,
            surface = Color.White,
            onSurface = SirenColors.Ink,
            surfaceVariant = SirenColors.Soft,
        ),
        content = content,
    )
}

/** Demo device credentials from database/demo/V100__demo_seed.sql. */
data class DemoPreset(val name: String, val role: String, val deviceId: String, val psk: String)

val DEMO_PRESETS = listOf(
    DemoPreset("Tomasz Lewandowski", "Kraków · liaison officer · can raise drone strike", "0c000000-0000-0000-0000-000000003345", "demo-psk-tomasz"),
    DemoPreset("Ania Kowalska", "Kraków · civil protection volunteer", "0c000000-0000-0000-0000-000000043572", "demo-psk-ania"),
    DemoPreset("Maja Wiśniewska", "Kraków · evacuation coordinator", "0c000000-0000-0000-0000-000000005623", "demo-psk-maja"),
    DemoPreset("Jacek Zieliński", "Kraków · civil protection volunteer", "0c000000-0000-0000-0000-000000009671", "demo-psk-jacek"),
    DemoPreset("Ewa Dąbrowska", "School No. 5 · principal (air raid)", "0c000000-0000-0000-0000-000000007710", "demo-psk-ewa"),
    DemoPreset("Katarzyna Nowak", "Kraków · duty officer · controller", "0c000000-0000-0000-0000-000000001001", "demo-psk-katarzyna"),
    DemoPreset("Dr Piotr Mazur", "Hospital · anesthesiologist", "0c000000-0000-0000-0000-000000011002", "demo-psk-piotr"),
    DemoPreset("Dr Robert Krawczyk", "Hospital · head of ED · controller", "0c000000-0000-0000-0000-000000011001", "demo-psk-robert"),
    DemoPreset("Sgt. Michał Piotrowski", "Army · squad leader", "0c000000-0000-0000-0000-000000021002", "demo-psk-michal"),
    DemoPreset("Capt. Adam Grabowski", "Army · commander · controller", "0c000000-0000-0000-0000-000000021001", "demo-psk-adam"),
)
