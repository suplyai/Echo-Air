package app.suply.echoair.ui.home

import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.suply.echoair.R
import app.suply.echoair.ui.locale.AppLocale
import app.suply.echoair.ui.locale.LanguagePickerSheet
import app.suply.echoair.ui.locale.LocaleManager
import app.suply.echoair.ui.locale.findActivity
import kotlinx.coroutines.delay

/**
 * Guided landing page for first-time users — a consignee, freight
 * forwarder, shipper or surveyor opening Echo Air on a loading dock,
 * possibly under time pressure. Two seconds to communicate what the
 * app is, what it'll ask, and where to start.
 *
 * Structure:
 *   1. Hero illustration that slowly cross-fades between a single-ULD
 *      scene (A) and a many-ULD scene (B). Conveys "same flow at any
 *      scale" — the auto-discovery does the work whether you're in
 *      front of one ULD or many.
 *   2. Headline: works at any moment on the supply chain — origin,
 *      hub, in-transit, arrival, post-arrival audit.
 *   3. Value proposition (the magic line): "AWB → all devices found
 *      automatically." This is the highest-leverage 14 words in the
 *      whole app, see HOME_VALUE_PROPOSITION in strings.xml.
 *   4. Two action cards. AWB-entry is primary (most users have the
 *      AWB number from documents before reaching the cargo). QR-scan
 *      is the alternative if a QR is in sight.
 *   5. Reassuring footer: "no account needed" — removes the unspoken
 *      "do I need to register?" friction that stops first-launch users.
 *
 * Top-right is a language pill (globe + current locale code). The
 * settings cog of pre-0.4.8 is intentionally gone — there are no
 * user-facing settings yet, exposing it created confusion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onScanQr: () -> Unit,
    onEnterAwb: () -> Unit,
    onEnterContainer: () -> Unit
) {
    var showLanguageSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val appContext = context.applicationContext
    val currentLocale = remember { LocaleManager.current() ?: AppLocale.DEFAULT }
    val reducedMotion = rememberReducedMotion()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                actions = {
                    LanguageChip(
                        locale = currentLocale,
                        onClick = { showLanguageSheet = true }
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            AlternatingHero(
                reducedMotion = reducedMotion,
                contentDescription = stringResource(R.string.home_hero_image_description)
            )

            // Two-paragraph intro. Same typography on both lines per the
            // v0.7.1 copy update — the first paragraph is no longer a
            // headline, it's instructional. Standard 8 dp paragraph gap
            // matches the rest of the column's vertical rhythm.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.home_intro_primary),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.home_intro_positioning),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            ActionCard(
                icon = Icons.Filled.Flight,
                title = stringResource(R.string.home_action_awb_title),
                subtitle = stringResource(R.string.home_action_awb_subtitle),
                onClick = onEnterAwb,
                primary = true
            )
            ActionCard(
                icon = Icons.Filled.DirectionsBoat,
                title = stringResource(R.string.home_enter_container_number),
                subtitle = stringResource(R.string.home_enter_container_number_help),
                onClick = onEnterContainer,
                primary = false
            )
            // QR is the third option — no subtitle: the single-line CTA
            // ("Scan QR code of device") is self-explanatory and keeps the
            // three-card stack visually balanced (two stacked text rows
            // would oversell what is just a fast-path).
            ActionCard(
                icon = Icons.Default.QrCodeScanner,
                title = stringResource(R.string.home_scan_qr_button),
                subtitle = null,
                onClick = onScanQr,
                primary = false
            )

            Text(
                stringResource(R.string.home_footer_no_account),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }

    if (showLanguageSheet) {
        LanguagePickerSheet(
            onSelect = { locale ->
                showLanguageSheet = false
                LocaleManager.apply(appContext, locale)
                // Force Activity recreate() so attachBaseContext re-reads the
                // stored tag and wraps Resources with the new Locale — without
                // this the visible strings don't swap until next cold start.
                context.findActivity()?.recreate()
            },
            onDismiss = { showLanguageSheet = false }
        )
    }
}

/**
 * Slow cross-fade between Hero A (single ULD) and Hero B (many ULDs).
 * 3 s hold + 600 ms fade per side. The animation pauses naturally
 * when the user navigates away — Compose destroys the composable, the
 * LaunchedEffect cancels — and resumes on return.
 *
 * Reduced-motion mode (TRANSITION_ANIMATION_SCALE or
 * ANIMATOR_DURATION_SCALE set to 0) shows ONLY Hero B as a static
 * image. Hero B is the more inclusive scene — multiple ULDs convey
 * "any scale" by themselves, where Hero A alone would communicate
 * "single-ULD app".
 */
@Composable
private fun AlternatingHero(reducedMotion: Boolean, contentDescription: String) {
    val heroModifier = Modifier
        .fillMaxWidth()
        .aspectRatio(8f / 5f)
        .padding(top = 4.dp)

    if (reducedMotion) {
        Image(
            painter = painterResource(R.drawable.home_hero_b),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = heroModifier
        )
        return
    }

    var showB by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 3 s hold per side. The Crossfade itself animates over 600 ms,
        // so the perceived cycle is roughly 3.6 s + 3.6 s = 7.2 s.
        while (true) {
            delay(3_000)
            showB = !showB
        }
    }
    Crossfade(
        targetState = showB,
        animationSpec = tween(durationMillis = 600),
        label = "homeHero"
    ) { isB ->
        Image(
            painter = painterResource(
                if (isB) R.drawable.home_hero_b else R.drawable.home_hero_a
            ),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = heroModifier
        )
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    primary: Boolean
) {
    val container =
        if (primary) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val onContainer =
        if (primary) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (primary) 2.dp else 0.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(28.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageChip(locale: AppLocale, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Icon(
            Icons.Default.Language,
            contentDescription = stringResource(R.string.home_language_selector_label),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            locale.displayCode,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Reads Android's TRANSITION_ANIMATION_SCALE and ANIMATOR_DURATION_SCALE.
 * Either being 0 indicates the user has system-level motion reduction
 * enabled; we honour that by stopping the hero cross-fade and showing
 * a single static image. Wrapped in runCatching because some OEM
 * skins throw on Settings.Global reads (Honor / EMUI seen).
 */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            val cr = context.contentResolver
            val transition = Settings.Global.getFloat(
                cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f
            )
            val animator = Settings.Global.getFloat(
                cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            )
            transition == 0f || animator == 0f
        }.getOrDefault(false)
    }
}
