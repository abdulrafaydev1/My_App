package com.instaguard.app.alarm

/**
 * Target Scan Items for Stage 2 CameraX & ML Kit Object Detection.
 * Each item contains a comprehensive set of synonyms and labels recognized by
 * Google ML Kit Image Labeling (on-device 400+ label model).
 */
enum class AlarmScanItem(
    val id: Int,
    val displayName: String,
    val matchedKeywords: Set<String>
) {
    LAPTOP(
        0,
        "Laptop",
        setOf("laptop", "computer", "personal computer", "netbook", "screen", "monitor", "display device", "electronic device", "technology", "multimedia")
    ),
    COMPUTER_MOUSE(
        1,
        "Computer Mouse",
        setOf("mouse", "computer mouse", "peripheral", "input device", "electronic device", "gadget", "computer hardware", "office equipment")
    ),
    COFFEE_MUG(
        2,
        "Coffee Mug",
        setOf("mug", "cup", "coffee cup", "teacup", "tableware", "drinkware", "serveware", "porcelain", "ceramic", "beverage")
    ),
    KEYBOARD(
        3,
        "Keyboard",
        setOf("keyboard", "computer keyboard", "space bar", "input device", "office equipment", "electronic instrument", "typing", "peripheral")
    ),
    WATER_BOTTLE(
        4,
        "Water Bottle",
        setOf("bottle", "water bottle", "plastic bottle", "glass bottle", "drinkware", "liquid", "container", "cylinder")
    ),
    BOOK_NOTEBOOK(
        5,
        "Book / Notebook",
        setOf("book", "notebook", "publication", "paper", "text", "handwriting", "document", "reading", "magazine")
    ),
    CHAIR_SOFA(
        6,
        "Chair / Couch",
        setOf("chair", "office chair", "furniture", "couch", "sofa", "armrest", "stool", "seat")
    ),
    SHOE_SLIPPER(
        7,
        "Shoe / Footwear",
        setOf("shoe", "footwear", "sneakers", "sandal", "slipper", "boot", "running shoe")
    );

    companion object {
        fun fromId(id: Int): AlarmScanItem {
            return entries.firstOrNull { it.id == id } ?: LAPTOP
        }

        fun randomItem(): AlarmScanItem {
            return entries.random()
        }

        fun getNextItem(currentItem: AlarmScanItem): AlarmScanItem {
            val all = entries
            val nextIndex = (currentItem.ordinal + 1) % all.size
            return all[nextIndex]
        }
    }
}
