package dev.shibasis.reaktor.io.xml

/**
 * Entity names [XmlReader] decodes beyond the five XML defines.
 *
 * XML only knows `amp`, `lt`, `gt`, `quot` and `apos`; everything else here is HTML, and appears in
 * feeds because generators paste titles and show notes straight out of a web page. A strict parser
 * stops the whole document at the first one. These are the ones that turn up in real feeds —
 * typography, currency, and the accented letters of Western European names — not the full HTML
 * table of two thousand. A name missing from here is kept as literal text, never dropped.
 */
internal val NamedEntities: Map<String, Int> = mapOf(
    "amp" to '&'.code, "lt" to '<'.code, "gt" to '>'.code, "quot" to '"'.code, "apos" to '\''.code,

    // Spacing and invisible marks
    "nbsp" to 0xA0, "ensp" to 0x2002, "emsp" to 0x2003, "thinsp" to 0x2009, "shy" to 0xAD,
    "zwnj" to 0x200C, "zwj" to 0x200D, "lrm" to 0x200E, "rlm" to 0x200F,

    // Punctuation
    "lsquo" to 0x2018, "rsquo" to 0x2019, "sbquo" to 0x201A, "ldquo" to 0x201C, "rdquo" to 0x201D,
    "bdquo" to 0x201E, "laquo" to 0xAB, "raquo" to 0xBB, "lsaquo" to 0x2039, "rsaquo" to 0x203A,
    "ndash" to 0x2013, "mdash" to 0x2014, "hellip" to 0x2026, "bull" to 0x2022, "middot" to 0xB7,
    "prime" to 0x2032, "Prime" to 0x2033, "dagger" to 0x2020, "Dagger" to 0x2021, "permil" to 0x2030,
    "iexcl" to 0xA1, "iquest" to 0xBF, "sect" to 0xA7, "para" to 0xB6, "brvbar" to 0xA6,

    // Symbols
    "copy" to 0xA9, "reg" to 0xAE, "trade" to 0x2122, "deg" to 0xB0, "plusmn" to 0xB1,
    "times" to 0xD7, "divide" to 0xF7, "micro" to 0xB5, "frac12" to 0xBD, "frac14" to 0xBC,
    "frac34" to 0xBE, "sup1" to 0xB9, "sup2" to 0xB2, "sup3" to 0xB3, "ordf" to 0xAA, "ordm" to 0xBA,
    "not" to 0xAC, "macr" to 0xAF, "acute" to 0xB4, "cedil" to 0xB8, "uml" to 0xA8,
    "larr" to 0x2190, "rarr" to 0x2192, "uarr" to 0x2191, "darr" to 0x2193, "harr" to 0x2194,
    "hearts" to 0x2665, "spades" to 0x2660, "clubs" to 0x2663, "diams" to 0x2666, "star" to 0x2606,
    "check" to 0x2713,

    // Currency
    "euro" to 0x20AC, "pound" to 0xA3, "cent" to 0xA2, "yen" to 0xA5, "curren" to 0xA4,

    // Letters
    "Agrave" to 0xC0, "Aacute" to 0xC1, "Acirc" to 0xC2, "Atilde" to 0xC3, "Auml" to 0xC4,
    "Aring" to 0xC5, "AElig" to 0xC6, "Ccedil" to 0xC7, "Egrave" to 0xC8, "Eacute" to 0xC9,
    "Ecirc" to 0xCA, "Euml" to 0xCB, "Igrave" to 0xCC, "Iacute" to 0xCD, "Icirc" to 0xCE,
    "Iuml" to 0xCF, "ETH" to 0xD0, "Ntilde" to 0xD1, "Ograve" to 0xD2, "Oacute" to 0xD3,
    "Ocirc" to 0xD4, "Otilde" to 0xD5, "Ouml" to 0xD6, "Oslash" to 0xD8, "Ugrave" to 0xD9,
    "Uacute" to 0xDA, "Ucirc" to 0xDB, "Uuml" to 0xDC, "Yacute" to 0xDD, "THORN" to 0xDE,
    "szlig" to 0xDF, "agrave" to 0xE0, "aacute" to 0xE1, "acirc" to 0xE2, "atilde" to 0xE3,
    "auml" to 0xE4, "aring" to 0xE5, "aelig" to 0xE6, "ccedil" to 0xE7, "egrave" to 0xE8,
    "eacute" to 0xE9, "ecirc" to 0xEA, "euml" to 0xEB, "igrave" to 0xEC, "iacute" to 0xED,
    "icirc" to 0xEE, "iuml" to 0xEF, "eth" to 0xF0, "ntilde" to 0xF1, "ograve" to 0xF2,
    "oacute" to 0xF3, "ocirc" to 0xF4, "otilde" to 0xF5, "ouml" to 0xF6, "oslash" to 0xF8,
    "ugrave" to 0xF9, "uacute" to 0xFA, "ucirc" to 0xFB, "uuml" to 0xFC, "yacute" to 0xFD,
    "thorn" to 0xFE, "yuml" to 0xFF, "OElig" to 0x152, "oelig" to 0x153, "Scaron" to 0x160,
    "scaron" to 0x161, "Yuml" to 0x178, "fnof" to 0x192,
)
