package org.citron.citron_emu.wn

import java.util.Locale

/**
 * Derives sensible default emulated region / language from the Android system locale, so a
 * user who never opens the Switch settings still gets a locale-appropriate region + language
 * instead of a hardcoded USA/English. Indices match citron's Region / Language enums (see the
 * emulator's res regionValues/languageValues and core settings_types.h):
 *
 * region_index:  Japan=0, USA=1, Europe=2, Australia=3, China=4, Korea=5, Taiwan=6
 * language_index: Japanese=0, English=1, French=2, German=3, Italian=4, Spanish=5, Chinese=6,
 *   Korean=7, Dutch=8, Portuguese=9, Russian=10, Taiwanese=11, BritishEnglish=12,
 *   CanadianFrench=13, LatinAmericanSpanish=14, SimplifiedChinese=15, TraditionalChinese=16,
 *   BrazilianPortuguese=17
 */
object SwitchLocaleDefaults {

    private val EUROPE_COUNTRIES = setOf(
        "GB", "IE", "FR", "DE", "ES", "IT", "NL", "BE", "PT", "AT", "CH", "SE", "NO",
        "DK", "FI", "IS", "PL", "CZ", "SK", "HU", "RO", "BG", "GR", "HR", "SI", "RS",
        "BA", "MK", "AL", "ME", "XK", "LT", "LV", "EE", "LU", "MT", "CY", "LI", "MC",
        "AD", "SM", "VA", "MD", "UA", "BY", "RU", "TR",
    )

    private val LATAM_COUNTRIES = setOf(
        "MX", "AR", "CO", "CL", "PE", "VE", "EC", "GT", "CU", "BO", "DO", "HN", "PY",
        "SV", "NI", "CR", "PA", "UY", "PR", "US", "419",
    )

    fun defaultRegion(): Int {
        val locale = Locale.getDefault()
        val country = locale.country.uppercase(Locale.US)
        val lang = locale.language.lowercase(Locale.US)
        return when {
            country == "JP" -> 0
            country == "US" || country == "CA" -> 1
            country == "KR" -> 5
            country == "CN" || country == "HK" || country == "MO" -> 4
            country == "TW" -> 6
            country == "AU" || country == "NZ" -> 3
            country in EUROPE_COUNTRIES -> 2
            // Fall back to the language when the country is unknown/empty.
            lang == "ja" -> 0
            lang == "ko" -> 5
            lang == "zh" -> if (country == "TW" || country == "HK" || country == "MO") 6 else 4
            lang == "de" || lang == "fr" || lang == "es" || lang == "it" || lang == "nl" ||
                lang == "pt" || lang == "ru" || lang == "sv" || lang == "pl" || lang == "cs" ||
                lang == "tr" || lang == "uk" -> 2
            else -> 1
        }
    }

    fun defaultLanguage(): Int {
        val locale = Locale.getDefault()
        val country = locale.country.uppercase(Locale.US)
        return when (locale.language.lowercase(Locale.US)) {
            "ja" -> 0
            "en" -> if (country in setOf("GB", "AU", "NZ", "IE", "ZA")) 12 else 1
            "fr" -> if (country == "CA") 13 else 2
            "de" -> 3
            "it" -> 4
            "es" -> if (country in LATAM_COUNTRIES) 14 else 5
            "zh" -> if (country == "TW" || country == "HK" || country == "MO") 16 else 15
            "ko" -> 7
            "nl" -> 8
            "pt" -> if (country == "BR") 17 else 9
            "ru" -> 10
            else -> 1
        }
    }
}
