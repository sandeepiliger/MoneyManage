package ai.labs32.khaata.core.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Commute
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Elderly
import androidx.compose.material.icons.filled.Escalator
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HomeRepairService
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Toll
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Maps a category's stored [ai.labs32.khaata.core.model.Category.iconKey] to a drawable icon.
 *
 * The key is a plain string rather than a resource id or an enum because it is written to the
 * database and to backup files. Storing a resource id would break the moment the build renumbered
 * them; storing an enum would make an old backup fail to deserialise the day a constant is
 * removed. A string that no longer maps simply falls back to a neutral icon, which is a cosmetic
 * loss rather than a data loss.
 *
 * Lives in `core/ui` rather than in a feature so the picker, the transaction row, the reports and
 * the category manager all resolve the same key to the same glyph.
 */
object CategoryIcons {

    /** The icon shown when a key is unknown, empty, or belongs to a newer version. */
    val fallback: ImageVector = Icons.Default.Category

    private val byKey: Map<String, ImageVector> = mapOf(
        // Food
        "food" to Icons.Default.Restaurant,
        "groceries" to Icons.Default.ShoppingCart,
        "restaurant" to Icons.Default.Restaurant,
        "delivery" to Icons.Default.DeliveryDining,
        "coffee" to Icons.Default.LocalCafe,
        "snacks" to Icons.Default.Kitchen,

        // Transport
        "transport" to Icons.Default.Commute,
        "fuel" to Icons.Default.LocalGasStation,
        "cab" to Icons.Default.LocalTaxi,
        "auto" to Icons.AutoMirrored.Filled.DirectionsBike,
        "metro" to Icons.Default.DirectionsBus,
        "parking" to Icons.Default.LocalParking,
        "toll" to Icons.Default.Toll,
        "car_service" to Icons.Default.DirectionsCar,

        // Bills and utilities
        "bills" to Icons.Default.ReceiptLong,
        "electricity" to Icons.Default.Bolt,
        "water" to Icons.Default.WaterDrop,
        "wifi" to Icons.Default.Wifi,
        "mobile" to Icons.Default.PhoneAndroid,
        "gas" to Icons.Default.LocalLaundryService,
        "tv" to Icons.Default.Tv,
        "building" to Icons.Default.Apartment,

        // Home
        "home" to Icons.Default.Home,
        "rent" to Icons.Default.Home,
        "help" to Icons.Default.CleaningServices,
        "repair" to Icons.Default.HomeRepairService,

        // Lifestyle
        "lifestyle" to Icons.Default.ShoppingBag,
        "shopping" to Icons.Default.ShoppingBag,
        "entertainment" to Icons.Default.Movie,
        "subscription" to Icons.Default.Subscriptions,
        "travel" to Icons.Default.Flight,
        "fitness" to Icons.Default.FitnessCenter,
        "personal_care" to Icons.Default.Spa,
        "gift" to Icons.Default.CardGiftcard,

        // Health
        "health" to Icons.Default.HealthAndSafety,
        "medicine" to Icons.Default.LocalPharmacy,
        "doctor" to Icons.Default.LocalHospital,
        "insurance" to Icons.Default.Security,

        // Financial
        "financial" to Icons.Default.AccountBalance,
        "emi" to Icons.Default.Payments,
        "loan" to Icons.Default.Payments,
        "investment" to Icons.Default.TrendingUp,
        "sip" to Icons.Default.Savings,
        "bank" to Icons.Default.AccountBalance,
        "tax" to Icons.Default.Description,

        // Family
        "family" to Icons.Default.FamilyRestroom,
        "children" to Icons.Default.ChildCare,
        "education" to Icons.Default.School,
        "parents" to Icons.Default.Elderly,

        // Income
        "salary" to Icons.Default.Work,
        "business" to Icons.Default.Business,
        "freelance" to Icons.Default.AccountBalanceWallet,
        "interest" to Icons.Default.CurrencyRupee,
        "rental" to Icons.Default.Escalator,
        "refund" to Icons.Default.Redeem,
        "other_income" to Icons.Default.Receipt,

        // Fallback key used by the seeded "Uncategorised" row
        "unknown" to Icons.Default.Category,

        // Generic keys a user can pick when creating their own category
        "category" to Icons.Default.Category,
        "wallet" to Icons.Default.AccountBalanceWallet,
        "build" to Icons.Default.Build,
    )

    /** Every key a user can choose from when creating or editing a category. */
    val pickableKeys: List<String> = listOf(
        "category", "food", "groceries", "restaurant", "coffee", "delivery",
        "transport", "fuel", "cab", "metro", "parking", "car_service",
        "bills", "electricity", "water", "wifi", "mobile", "tv",
        "home", "rent", "repair", "shopping", "entertainment", "subscription",
        "travel", "fitness", "personal_care", "gift", "health", "medicine",
        "doctor", "insurance", "emi", "loan", "investment", "sip", "bank", "tax",
        "family", "children", "education", "parents", "salary", "business",
        "freelance", "refund", "wallet", "build",
    )

    operator fun get(iconKey: String?): ImageVector = byKey[iconKey] ?: fallback
}
