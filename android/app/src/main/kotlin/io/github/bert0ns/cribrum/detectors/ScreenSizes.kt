// Screen resolutions used to recognize screenshots. Mirrors lab/src/cribrum_lab/screens.py: add a
// resolution there and here, for any device whose screenshots end up on the phone.
package io.github.bert0ns.cribrum.detectors

/** Known screen resolutions as (short side, long side), in pixels. */
val SCREEN_SIZES: Set<Pair<Int, Int>> = setOf(
    // Phones
    720 to 1280, 720 to 1440, 720 to 1520, 720 to 1560, 720 to 1600, 720 to 1612,
    750 to 1334, 828 to 1792, 1080 to 1920, 1080 to 2160, 1080 to 2220, 1080 to 2240,
    1080 to 2280, 1080 to 2310, 1080 to 2340, 1080 to 2376, 1080 to 2400, 1080 to 2408,
    1080 to 2412, 1080 to 2520, 1080 to 2640, 1125 to 2436, 1170 to 2532, 1179 to 2556,
    1206 to 2622, 1220 to 2712, 1242 to 2208, 1242 to 2688, 1260 to 2800, 1280 to 2856,
    1284 to 2778, 1290 to 2796, 1320 to 2868, 1344 to 2992, 1440 to 2560, 1440 to 2960,
    1440 to 3040, 1440 to 3088, 1440 to 3120, 1440 to 3200,
    // Foldables, inner screens
    1768 to 2208, 1812 to 2176, 1856 to 2160, 1968 to 2184,
    // Tablets
    800 to 1280, 1200 to 1920, 1200 to 2000, 1600 to 2560, 1620 to 2160, 1640 to 2360,
    1668 to 2388, 2048 to 2732,
    // Monitors and laptops
    768 to 1366, 900 to 1440, 864 to 1536, 900 to 1600, 1050 to 1680, 1200 to 1920,
    1440 to 2560, 1600 to 2560, 1800 to 2880, 2160 to 3840,
)
