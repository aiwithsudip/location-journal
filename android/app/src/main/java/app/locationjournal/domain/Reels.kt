package app.locationjournal.domain

data class SeedComment(
    val id: String,
    val author: String,
    val text: String,
    val t: Long,
)

data class Reel(
    val id: String,
    val title: String,
    val place: String,
    val caption: String,
    val sound: String,
    val likes: Int,
    val saves: Int,
    val shares: Int,
    val reshares: Int,
    val views: Int,
    val comments: List<SeedComment>,
)

val REEL_CATALOG = listOf(
    Reel("reel-01", "Home lane at dawn", "Home lane", "The street is still wet and the bicycle has not moved.", "Quiet lane", 128, 18, 6, 2, 840, listOf(SeedComment("seed-01-a", "Mina", "That light only lasts a few minutes.", 1), SeedComment("seed-01-b", "Arun", "I know this corner.", 2))),
    Reel("reel-02", "Morning market", "Spice lane", "Turmeric, chili, and the first customers.", "Market murmur", 256, 40, 14, 5, 1900, listOf(SeedComment("seed-02-a", "Sita", "The colors are the whole story.", 3))),
    Reel("reel-03", "Riverside", "River bank", "The boat stays tied while the water moves.", "River", 96, 11, 4, 1, 640, listOf(SeedComment("seed-03-a", "Bikash", "Midday and still this calm.", 4))),
    Reel("reel-04", "Tea house", "Tea house", "Cups out, kettle warm, nobody in a hurry.", "Kettle", 310, 52, 20, 7, 2400, listOf(SeedComment("seed-04-a", "Nima", "Saving this for a slow afternoon.", 5))),
    Reel("reel-05", "Temple courtyard", "Courtyard", "Carved wood and a long afternoon shadow.", "Courtyard", 174, 22, 9, 3, 1100, listOf(SeedComment("seed-05-a", "Kabita", "The bell is just out of frame.", 6))),
    Reel("reel-06", "Rice fields", "Fields", "A path through the green, hills still blue.", "Open air", 402, 61, 25, 8, 3200, listOf(SeedComment("seed-06-a", "Ramesh", "Sunrise is the right hour for this.", 7))),
    Reel("reel-07", "Night bus", "On the road", "Rain on the glass and the town going by.", "Rain", 88, 9, 3, 1, 510, listOf(SeedComment("seed-07-a", "Pooja", "This feels like the ride home.", 8))),
    Reel("reel-08", "Rooftop", "Rooftop", "The washing line catches the last sun.", "Evening wind", 143, 16, 7, 2, 980, listOf(SeedComment("seed-08-a", "Sunil", "Stay for the color change.", 9))),
    Reel("reel-09", "Night stall", "Food lane", "Steam, warm bulbs, and a dark lane behind.", "Night stall", 221, 33, 12, 4, 1500, listOf(SeedComment("seed-09-a", "Asha", "I can almost smell it.", 10))),
    Reel("reel-10", "Forest bridge", "Forest path", "A short bridge over clear water.", "Stream", 267, 28, 11, 3, 1700, listOf(SeedComment("seed-10-a", "Dipak", "The green is doing all the work.", 11))),
)
