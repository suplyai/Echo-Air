package app.suply.echoair.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.suply.echoair.R
import app.suply.echoair.data.api.ShipmentDto
import app.suply.echoair.ui.haptics.EchoHaptics

/**
 * Bottom-sheet identity confirmation for a resolved shipment.
 *
 * This is the "trust moment" — the app has taken the user's input (QR, vision
 * AI, or typed AWB), resolved it against the backend, and is showing enough
 * identity data back that the consignee can say "yes, this is my shipment"
 * with confidence. If they typed the wrong AWB or pasted the wrong barcode,
 * this is where they catch it before committing to a scan.
 *
 * Hero: commodity name + category-derived icon / accent colour.
 * Supporting: AWB, transport mode badge, origin→destination route, device
 * count. Primary CTA "Start scanning" fires a haptic tick and proceeds.
 *
 * Kept in ConfirmShipmentDialog.kt despite now being a sheet — file name
 * preserves the import path for minimal churn across call sites.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmShipmentSheet(
    shipment: ShipmentDto,
    confidence: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val appContext = LocalContext.current.applicationContext
    // Prefer the root-level commodity fields the backend now sends
    // (commodity_name / commodity_category). Fall back to the older nested
    // cargo_profile shape so responses from services that haven't caught up
    // still render the hero correctly.
    val commodityName = shipment.commodityName ?: shipment.cargoProfile?.name
    val commodityCategory = shipment.commodityCategory ?: shipment.cargoProfile?.category
    val accent = CommodityAccent.forCategory(commodityCategory)

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Low-confidence OCR warning — only shows on vision-AI path with low
            // confidence; QR and manual AWB paths pass confidence = "high" or null.
            if (confidence == "low" || confidence == "medium") {
                Text(
                    text = stringResource(R.string.confirm_heading_low_confidence),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            // Hero
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(accent.colour.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = accent.icon,
                        contentDescription = null,
                        tint = accent.colour,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = commodityName ?: stringResource(R.string.confirm_fallback_commodity),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 26.sp
                    )
                    commodityCategory
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            Text(
                                text = it.replaceFirstChar { c -> c.uppercase() },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                }
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant)

            // AWB + transport mode badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.confirm_label_air_waybill),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = shipment.airwayBillNumber,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                TransportModeBadge(shipment.transportMode)
            }

            // Origin → Destination
            val origin = formatEndpoint(shipment.airOriginCity, shipment.airOriginIata)
            val dest = formatEndpoint(shipment.airDestCity, shipment.airDestIata)
            if (origin != null || dest != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = origin ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Filled.Flight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = dest ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant)

            // Device count + (Multiple Package Shipment) breakdown when
            // the shipment has more than one unit. Single-unit shipments
            // render exactly as pre-0.4.9 — just "%d devices to collect".
            //
            // Multi-unit rendering follows the air-cargo convention: full
            // term "Multiple Package Shipment" on first reference (badge
            // above the count line), MPS acronym used everywhere after,
            // unit count rendered with the localised unit noun, and a
            // verbatim list of customer-supplied unit labels below as a
            // brief breakdown so the consignee knows what to expect at
            // the cargo before they walk to it.
            val deviceCount = shipment.devices.size
            val unitCount = shipment.units.size
            val isMultiUnit = unitCount > 1

            if (isMultiUnit) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Text(
                        text = stringResource(R.string.confirm_mps_badge),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = when {
                    deviceCount == 0 -> stringResource(R.string.confirm_no_devices_expected)
                    isMultiUnit -> stringResource(
                        R.string.confirm_mps_summary,
                        unitCount,
                        deviceCount
                    )
                    else -> pluralStringResource(
                        R.plurals.confirm_devices_to_collect,
                        deviceCount,
                        deviceCount
                    )
                },
                style = MaterialTheme.typography.titleMedium
            )

            // Per-unit breakdown — labels rendered verbatim from the
            // shipper's dashboard config ("ULD 1", "Pallet A", "Lote-247",
            // whatever). When a label is null/blank (the synthetic
            // "Unattributed" edge case), the localised fallback is used.
            // Truncates implicitly via softWrap so long unit lists wrap
            // gracefully without dominating the sheet.
            if (isMultiUnit) {
                val fallback = stringResource(R.string.collection_unattributed_unit)
                val breakdown = shipment.units
                    .map { it.label?.takeIf { l -> l.isNotBlank() } ?: fallback }
                    .joinToString(separator = "  ·  ")
                Text(
                    text = breakdown,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // CTAs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    onClick = {
                        EchoHaptics.tick(appContext)
                        onConfirm()
                    },
                    modifier = Modifier
                        .weight(2f)
                        .height(52.dp)
                ) {
                    Text(
                        stringResource(R.string.confirm_start_scanning),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

/**
 * Back-compat shim: the stateless app still calls ConfirmShipmentDialog(...)
 * from two places. Kept as a thin alias so we don't have to touch call
 * sites in this commit; delete once callers migrate to the Sheet name.
 */
@Composable
fun ConfirmShipmentDialog(
    shipment: ShipmentDto,
    confidence: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) = ConfirmShipmentSheet(shipment, confidence, onConfirm, onCancel)

@Composable
private fun TransportModeBadge(mode: String?) {
    val text: String? = when {
        mode == null -> null
        mode.equals("air", ignoreCase = true) || mode.equals("air_freight", ignoreCase = true) ->
            stringResource(R.string.confirm_badge_air_freight)
        else -> mode.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    if (text == null) return
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** "Lima (LIM)" when we have both; "LIM" if only code; null if neither. */
private fun formatEndpoint(city: String?, code: String?): String? = when {
    !city.isNullOrBlank() && !code.isNullOrBlank() -> "$city ($code)"
    !code.isNullOrBlank() -> code
    !city.isNullOrBlank() -> city
    else -> null
}
