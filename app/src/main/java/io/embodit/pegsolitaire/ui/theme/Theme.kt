package io.embodit.pegsolitaire.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.pegsolitaire.domain.Language
import io.embodit.pegsolitaire.platform.Graph
import java.util.Locale

/** design-package theme.colorScheme: a classical malachite set (deep teal UI, malachite green, ivory, gilt), solid ink labels. */
object PsColors {
    val Primary = Color(0xFF1E6B73)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFBFE3E6)
    val OnPrimaryContainer = Color(0xFF062F34)
    val Secondary = Color(0xFF0A4224)
    val OnSecondary = Color(0xFFFFFFFF)
    val SecondaryContainer = Color(0xFFECE0C4)
    val OnSecondaryContainer = Color(0xFF3A2410)
    val Tertiary = Color(0xFFD4A64A)
    val OnTertiary = Color(0xFF2B2200)
    val TertiaryContainer = Color(0xFFF4E4A8)
    val OnTertiaryContainer = Color(0xFF3E3000)
    val Error = Color(0xFFC8423F)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F23)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF3A3F44)
    /** Error text on glass / light cards; the brighter Error stays for button fills. */
    val ErrorText = Color(0xFF7A1A18)
    val Amber = Color(0xFFB97A12)

    // diagrams / plate / cards
    val Malachite = Color(0xFF0A4224)
    val MalachiteLight = Color(0xFF1A7440)
    val Ivory = Color(0xFFECDAB6)
    val Ball = Color(0xFF1A7440)
    val TagFace = Color(0xFFFBF6EC)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). The language setting localises resources below this
 * point through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun PegTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = PsColors.OnPrimary,
        labelPrimary = PsColors.Ink,
        labelSecondary = PsColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = PsColors.Error,
        alert = PsColors.Amber,
        passable = PsColors.Secondary,
        interaction = PsColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
