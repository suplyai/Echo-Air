package app.suply.echoair.ui.capture

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Icecream
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SetMeal
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Maps a commodity category string from the backend to a hero icon +
 * accent colour for the confirmation sheet. Matching is case-insensitive
 * and tolerant of common synonyms so a single mapping table covers both
 * the canonical values ("flowers", "seafood", …) and the freeform text
 * that sometimes shows up in legacy rows ("cut flowers", "fish", …).
 *
 * When the category is null or unknown the sheet falls back to a neutral
 * cargo icon so it's never visually broken.
 */
internal data class CommodityAccent(
    val icon: ImageVector,
    val colour: Color
) {
    companion object {
        private val FLOWERS  = CommodityAccent(Icons.Filled.LocalFlorist,   Color(0xFFD81B60)) // rose
        private val SEAFOOD  = CommodityAccent(Icons.Filled.SetMeal,        Color(0xFF00838F)) // deep cyan
        private val PHARMA   = CommodityAccent(Icons.Filled.LocalPharmacy,  Color(0xFF1565C0)) // clinical blue
        private val PRODUCE  = CommodityAccent(Icons.Filled.Grass,          Color(0xFF2E7D32)) // green
        private val MEAT     = CommodityAccent(Icons.Filled.Restaurant,     Color(0xFFB71C1C)) // deep red
        private val DAIRY    = CommodityAccent(Icons.Filled.Icecream,       Color(0xFF6D4C41)) // warm brown
        private val DEFAULT  = CommodityAccent(Icons.Filled.Inventory2,     Color(0xFF455A64)) // blue-grey

        fun forCategory(category: String?): CommodityAccent {
            val key = category?.trim()?.lowercase() ?: return DEFAULT
            return when {
                key.contains("flower") || key.contains("bouquet") || key == "floral" -> FLOWERS
                key.contains("seafood") || key.contains("fish") || key.contains("shellfish") -> SEAFOOD
                key.contains("pharma") || key.contains("medicin") || key.contains("vaccine") -> PHARMA
                key.contains("fruit") || key.contains("vegetable") || key.contains("produce")
                    || key.contains("citrus") || key.contains("berry") -> PRODUCE
                key.contains("meat") || key.contains("beef") || key.contains("pork")
                    || key.contains("lamb") || key.contains("poultry") || key.contains("chicken") -> MEAT
                key.contains("dairy") || key.contains("milk") || key.contains("cheese")
                    || key.contains("yoghurt") || key.contains("yogurt") -> DAIRY
                else -> DEFAULT
            }
        }
    }
}
