package pl.siren.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("siren")
data class SirenProperties(
    val alarmGrace: Duration = Duration.ofHours(24),
    val maxClockSkew: Duration = Duration.ofMinutes(5),
    val positionRetention: Duration = Duration.ofHours(24),
    val keyEpochValidity: Duration = Duration.ofDays(30),
    val demo: Demo = Demo(),
) {
    data class Demo(val enabled: Boolean = false)
}
