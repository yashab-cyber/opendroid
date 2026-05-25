package com.opendroid.ai.core.agent

data class ActionDefinition(
    val name: String,
    val description: String,
    val params: List<ParamDefinition>,
    val examples: List<String>,
    val category: ActionCategory,
    val isSimple: Boolean = true
)

data class ParamDefinition(
    val name: String,
    val type: ParamType,
    val required: Boolean,
    val description: String,
    val enumValues: List<String> = emptyList(),
    val defaultValue: Any? = null
)

enum class ParamType { STRING, INT, BOOLEAN, ENUM }

enum class ActionCategory {
    SYSTEM, COMMUNICATION, PRODUCTIVITY,
    INFORMATION, TRANSPORT, MEDIA,
    SHOPPING, FINANCE, SMART_HOME,
    MACRO, ADVANCED, AGENT
}

object ActionSchema {

    val ALL_ACTIONS = listOf(

        // ── SYSTEM ──────────────────────────────────────

        ActionDefinition(
            name = "OPEN_APP",
            description = "Opens any installed app on the device",
            params = listOf(ParamDefinition("appName", ParamType.STRING, true, "Name of the app to open")),
            examples = listOf("open instagram", "open whatsapp", "launch camera", "start spotify"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "TOGGLE_FLASHLIGHT",
            description = """Turns flashlight/torch on, off, or toggles.
                'toggle' flips current state automatically.
                No need to ask user for state when using
                'flash', 'torch', 'flashlight' commands.""",
            params = listOf(
                ParamDefinition(
                    name = "state",
                    type = ParamType.ENUM,
                    required = false,
                    description = "Desired flashlight state",
                    enumValues = listOf("on", "off", "toggle"),
                    defaultValue = "toggle"
                )
            ),
            examples = listOf(
                "open flash", "open torch", "turn on flashlight",
                "torch off", "flash on", "flashlight"
            ),
            category = ActionCategory.SYSTEM,
            isSimple = true
        ),
        ActionDefinition(
            name = "TAKE_SCREENSHOT",
            description = "Takes a screenshot of current screen",
            params = emptyList(),
            examples = listOf("take screenshot", "screenshot", "capture screen", "screengrab"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "LOCK_SCREEN",
            description = "Locks the phone screen",
            params = emptyList(),
            examples = listOf("lock phone", "lock screen", "screen off"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "TOGGLE_WIFI",
            description = "Turns WiFi on or off",
            params = listOf(ParamDefinition("state", ParamType.ENUM, true, "WiFi state", listOf("on", "off"))),
            examples = listOf("wifi on", "turn off wifi", "enable wifi"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "TOGGLE_BLUETOOTH",
            description = "Turns Bluetooth on or off",
            params = listOf(ParamDefinition("state", ParamType.ENUM, true, "Bluetooth state", listOf("on", "off"))),
            examples = listOf("bluetooth on", "turn off bluetooth"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "TOGGLE_MOBILE_DATA",
            description = "Turns mobile data on or off",
            params = listOf(ParamDefinition("state", ParamType.ENUM, true, "Mobile data state", listOf("on", "off"))),
            examples = listOf("data on", "turn off mobile data"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "TOGGLE_HOTSPOT",
            description = "Turns hotspot on or off",
            params = listOf(ParamDefinition("state", ParamType.ENUM, true, "Hotspot state", listOf("on", "off"))),
            examples = listOf("hotspot on", "turn on personal hotspot"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "TOGGLE_DND",
            description = "Turns Do Not Disturb mode on or off",
            params = listOf(ParamDefinition("state", ParamType.ENUM, true, "DND state", listOf("on", "off"))),
            examples = listOf("dnd on", "do not disturb", "silent mode"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "SET_BRIGHTNESS",
            description = "Sets screen brightness level. If no level given, defaults to 50%.",
            params = listOf(ParamDefinition("level", ParamType.INT, false, "Brightness 0-100", defaultValue = 50)),
            examples = listOf("set brightness", "brightness 50", "set brightness to 80%", "max brightness", "set brightness to 100", "dim screen", "brightness low"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "SET_VOLUME",
            description = "Sets device volume. Type defaults to media if not specified.",
            params = listOf(
                ParamDefinition("type", ParamType.ENUM, false, "Volume type", listOf("media", "ring", "alarm", "notification", "system"), "media"),
                ParamDefinition("level", ParamType.INT, true, "Volume level 0-100")
            ),
            examples = listOf("set volume to 50", "volume 80", "set volume to 100%", "volume up", "mute", "set media volume to 50", "set ringtone volume to 70"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "RESTART_DEVICE",
            description = "Restarts the device",
            params = emptyList(),
            examples = listOf("restart phone", "reboot device"),
            category = ActionCategory.SYSTEM,
            isSimple = false
        ),
        ActionDefinition(
            name = "SET_WALLPAPER",
            description = "Sets the device wallpaper",
            params = listOf(ParamDefinition("imageUrl", ParamType.STRING, true, "Image URL or path")),
            examples = listOf("set wallpaper", "change wallpaper"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "RECORD_SCREEN",
            description = "Records the screen for a duration",
            params = listOf(ParamDefinition("duration", ParamType.STRING, false, "Duration in seconds", defaultValue = "30")),
            examples = listOf("record screen", "screen record"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "INSTALL_APP",
            description = "Opens Play Store to install an app",
            params = listOf(ParamDefinition("appName", ParamType.STRING, true, "App name to install")),
            examples = listOf("install telegram", "download app"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "GET_SYSTEM_INFO",
            description = "Gets system information like battery, storage, RAM",
            params = emptyList(),
            examples = listOf("battery level", "system info", "storage space"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "SET_RINGER_MODE",
            description = "Sets ringer mode",
            params = listOf(ParamDefinition("mode", ParamType.ENUM, true, "Ringer mode", listOf("normal", "vibrate", "silent"))),
            examples = listOf("vibrate mode", "silent mode", "normal ringer"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "CLOSE_APP",
            description = "Closes the current foreground app",
            params = emptyList(),
            examples = listOf("close app", "exit app"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "ANALYZE_SCREENSHOT",
            description = "Takes screenshot and analyzes screen content with AI vision",
            params = listOf(ParamDefinition("question", ParamType.STRING, false, "What to analyze", defaultValue = "What do you see on screen?")),
            examples = listOf("what's on my screen", "analyze screen", "read what's on screen"),
            category = ActionCategory.SYSTEM
        ),

        // ── COMMUNICATION ───────────────────────────────

        ActionDefinition(
            name = "MAKE_CALL",
            description = "Makes a phone call to a contact or number",
            params = listOf(ParamDefinition("contact", ParamType.STRING, true, "Contact name or phone number")),
            examples = listOf("call dad", "call mom", "call 9876543210", "phone John"),
            category = ActionCategory.COMMUNICATION
        ),
        ActionDefinition(
            name = "SEND_WHATSAPP",
            description = "Sends WhatsApp message. Opens WhatsApp internally.",
            params = listOf(
                ParamDefinition("contact", ParamType.STRING, true, "Contact name or number"),
                ParamDefinition("message", ParamType.STRING, true, "Message to send")
            ),
            examples = listOf("send hi to dad on whatsapp", "whatsapp mom I'm coming home"),
            category = ActionCategory.COMMUNICATION
        ),
        ActionDefinition(
            name = "SEND_WHATSAPP_GROUP",
            description = "Sends a message to a WhatsApp group",
            params = listOf(
                ParamDefinition("groupName", ParamType.STRING, true, "Group name"),
                ParamDefinition("message", ParamType.STRING, true, "Message to send")
            ),
            examples = listOf("send hi to family group on whatsapp"),
            category = ActionCategory.COMMUNICATION
        ),
        ActionDefinition(
            name = "SEND_SMS",
            description = "Sends an SMS text message",
            params = listOf(
                ParamDefinition("contact", ParamType.STRING, true, "Contact name or number"),
                ParamDefinition("message", ParamType.STRING, true, "SMS message text")
            ),
            examples = listOf("send sms to dad", "text mom"),
            category = ActionCategory.COMMUNICATION
        ),
        ActionDefinition(
            name = "SEND_EMAIL",
            description = "Sends an email",
            params = listOf(
                ParamDefinition("to", ParamType.STRING, true, "Recipient email or contact name"),
                ParamDefinition("subject", ParamType.STRING, true, "Email subject"),
                ParamDefinition("body", ParamType.STRING, true, "Email body"),
                ParamDefinition("cc", ParamType.STRING, false, "CC recipients"),
                ParamDefinition("attachments", ParamType.STRING, false, "Attachment paths")
            ),
            examples = listOf("send email to boss", "email John"),
            category = ActionCategory.COMMUNICATION,
            isSimple = false
        ),
        ActionDefinition(
            name = "MAKE_VIDEO_CALL",
            description = "Makes a video call via WhatsApp, Meet, or Zoom",
            params = listOf(
                ParamDefinition("contact", ParamType.STRING, true, "Contact name"),
                ParamDefinition("app", ParamType.ENUM, false, "Video call app", listOf("whatsapp", "meet", "zoom"), "whatsapp")
            ),
            examples = listOf("video call mom", "zoom call with team"),
            category = ActionCategory.COMMUNICATION
        ),
        ActionDefinition(
            name = "READ_MESSAGES",
            description = "Reads recent messages from an app",
            params = listOf(
                ParamDefinition("app", ParamType.STRING, false, "Messaging app", defaultValue = "sms"),
                ParamDefinition("count", ParamType.STRING, false, "Number of messages", defaultValue = "5")
            ),
            examples = listOf("read my messages", "show recent texts"),
            category = ActionCategory.COMMUNICATION
        ),
        ActionDefinition(
            name = "READ_EMAILS",
            description = "Reads recent emails",
            params = listOf(
                ParamDefinition("folder", ParamType.STRING, false, "Email folder", defaultValue = "inbox"),
                ParamDefinition("count", ParamType.STRING, false, "Number of emails", defaultValue = "5"),
                ParamDefinition("filter", ParamType.STRING, false, "Filter criteria")
            ),
            examples = listOf("read my emails", "check inbox"),
            category = ActionCategory.COMMUNICATION
        ),

        // ── PRODUCTIVITY ────────────────────────────────

        ActionDefinition(
            name = "SET_ALARM",
            description = """Sets an alarm at the specified time.
                Handles all time formats: '5 am', '5:30 AM',
                '17:00', 'noon', 'midnight', 'half past 6'.
                Opens clock app automatically.
                No confirmation needed — execute immediately.""",
            params = listOf(
                ParamDefinition("time", ParamType.STRING, true,
                    "Alarm time in any format: '5 am', '7:30', '14:00'"),
                ParamDefinition("label", ParamType.STRING, false,
                    "Alarm label or name", defaultValue = "Alarm"),
                ParamDefinition("repeat", ParamType.STRING, false,
                    "Repeat pattern: daily, weekdays, weekends, or specific days",
                    defaultValue = "once")
            ),
            examples = listOf(
                "set alarm 5 am", "wake me at 7",
                "alarm at 6:30", "set alarm for 8 tomorrow",
                "wake me up at noon"
            ),
            category = ActionCategory.PRODUCTIVITY,
            isSimple = true
        ),
        ActionDefinition(
            name = "SET_REMINDER",
            description = "Sets a reminder for a specific time",
            params = listOf(
                ParamDefinition("text", ParamType.STRING, true, "Reminder message"),
                ParamDefinition("datetime", ParamType.STRING, true, "When to remind"),
                ParamDefinition("repeat", ParamType.STRING, false, "Repeat schedule")
            ),
            examples = listOf("remind me to take medicine at 8pm"),
            category = ActionCategory.PRODUCTIVITY
        ),
        ActionDefinition(
            name = "SET_TIMER",
            description = "Starts a countdown timer",
            params = listOf(
                ParamDefinition("duration", ParamType.STRING, true, "Timer duration e.g. 5 minutes"),
                ParamDefinition("label", ParamType.STRING, false, "Timer label", defaultValue = "Timer")
            ),
            examples = listOf("set timer 5 minutes", "timer 30 seconds"),
            category = ActionCategory.PRODUCTIVITY
        ),
        ActionDefinition(
            name = "CREATE_CALENDAR_EVENT",
            description = "Creates a calendar event",
            params = listOf(
                ParamDefinition("title", ParamType.STRING, true, "Event title"),
                ParamDefinition("date", ParamType.STRING, true, "Event date"),
                ParamDefinition("time", ParamType.STRING, false, "Event time"),
                ParamDefinition("duration", ParamType.STRING, false, "Event duration", defaultValue = "1 hour"),
                ParamDefinition("location", ParamType.STRING, false, "Event location"),
                ParamDefinition("attendees", ParamType.STRING, false, "Comma-separated attendees")
            ),
            examples = listOf("create meeting tomorrow 3pm"),
            category = ActionCategory.PRODUCTIVITY,
            isSimple = false
        ),
        ActionDefinition(
            name = "ADD_NOTE",
            description = "Creates a new note",
            params = listOf(
                ParamDefinition("title", ParamType.STRING, false, "Note title", defaultValue = "Note"),
                ParamDefinition("content", ParamType.STRING, true, "Note content"),
                ParamDefinition("tags", ParamType.STRING, false, "Comma-separated tags")
            ),
            examples = listOf("add note buy milk", "note remember to call bank"),
            category = ActionCategory.PRODUCTIVITY
        ),
        ActionDefinition(
            name = "LIST_CALENDAR_TODAY",
            description = "Lists today's calendar events",
            params = emptyList(),
            examples = listOf("what's on my calendar today", "today's schedule"),
            category = ActionCategory.PRODUCTIVITY
        ),
        ActionDefinition(
            name = "LIST_CALENDAR_WEEK",
            description = "Lists this week's calendar events",
            params = emptyList(),
            examples = listOf("this week's schedule", "weekly calendar"),
            category = ActionCategory.PRODUCTIVITY
        ),
        ActionDefinition(
            name = "CREATE_TASK",
            description = "Creates a task/to-do item",
            params = listOf(
                ParamDefinition("title", ParamType.STRING, true, "Task title"),
                ParamDefinition("dueDate", ParamType.STRING, false, "Due date"),
                ParamDefinition("priority", ParamType.STRING, false, "Priority level"),
                ParamDefinition("list", ParamType.STRING, false, "Task list name")
            ),
            examples = listOf("create task buy groceries"),
            category = ActionCategory.PRODUCTIVITY
        ),
        ActionDefinition(
            name = "READ_NOTES",
            description = "Reads saved notes",
            params = listOf(ParamDefinition("filter", ParamType.STRING, false, "Filter criteria")),
            examples = listOf("read my notes", "show notes"),
            category = ActionCategory.PRODUCTIVITY
        ),

        // ── INFORMATION ─────────────────────────────────

        ActionDefinition(
            name = "WEB_SEARCH",
            description = "Searches the web for information",
            params = listOf(ParamDefinition("query", ParamType.STRING, true, "Search query")),
            examples = listOf("search best restaurants", "google latest iphone"),
            category = ActionCategory.INFORMATION
        ),
        ActionDefinition(
            name = "GET_WEATHER",
            description = "Gets current weather for a location",
            params = listOf(
                ParamDefinition("location", ParamType.STRING, false, "City name", defaultValue = "current location"),
                ParamDefinition("days", ParamType.STRING, false, "Forecast days", defaultValue = "1")
            ),
            examples = listOf("weather today", "weather in Mumbai", "forecast"),
            category = ActionCategory.INFORMATION
        ),
        ActionDefinition(
            name = "GET_NEWS",
            description = "Gets latest news on a topic",
            params = listOf(
                ParamDefinition("topic", ParamType.STRING, false, "News topic"),
                ParamDefinition("count", ParamType.STRING, false, "Number of articles", defaultValue = "5"),
                ParamDefinition("sources", ParamType.STRING, false, "Preferred sources")
            ),
            examples = listOf("latest news", "tech news", "cricket news"),
            category = ActionCategory.INFORMATION
        ),
        ActionDefinition(
            name = "CALCULATE",
            description = "Performs a mathematical calculation",
            params = listOf(ParamDefinition("expression", ParamType.STRING, true, "Math expression to calculate")),
            examples = listOf("calculate 15% of 2000", "what is 45 * 12"),
            category = ActionCategory.INFORMATION
        ),
        ActionDefinition(
            name = "TRANSLATE",
            description = "Translates text to another language",
            params = listOf(
                ParamDefinition("text", ParamType.STRING, true, "Text to translate"),
                ParamDefinition("from", ParamType.STRING, false, "Source language"),
                ParamDefinition("to", ParamType.STRING, true, "Target language")
            ),
            examples = listOf("translate hello to Hindi", "say good morning in Tamil"),
            category = ActionCategory.INFORMATION
        ),
        ActionDefinition(
            name = "DEFINE_WORD",
            description = "Gets the definition of a word",
            params = listOf(ParamDefinition("word", ParamType.STRING, true, "Word to define")),
            examples = listOf("define ephemeral", "what does serendipity mean"),
            category = ActionCategory.INFORMATION
        ),
        ActionDefinition(
            name = "CONVERT_UNITS",
            description = "Converts between units of measurement",
            params = listOf(
                ParamDefinition("value", ParamType.STRING, true, "Value to convert"),
                ParamDefinition("from", ParamType.STRING, true, "Source unit"),
                ParamDefinition("to", ParamType.STRING, true, "Target unit")
            ),
            examples = listOf("convert 5 miles to km", "100 celsius to fahrenheit"),
            category = ActionCategory.INFORMATION
        ),
        ActionDefinition(
            name = "CURRENCY_CONVERT",
            description = "Converts between currencies",
            params = listOf(
                ParamDefinition("amount", ParamType.STRING, true, "Amount to convert"),
                ParamDefinition("from", ParamType.STRING, true, "Source currency"),
                ParamDefinition("to", ParamType.STRING, true, "Target currency")
            ),
            examples = listOf("100 USD to INR", "convert 50 euros to dollars"),
            category = ActionCategory.INFORMATION
        ),
        ActionDefinition(
            name = "CHECK_STOCK",
            description = "Checks stock price",
            params = listOf(ParamDefinition("symbol", ParamType.STRING, true, "Stock ticker symbol")),
            examples = listOf("AAPL stock price", "check TSLA"),
            category = ActionCategory.INFORMATION
        ),
        ActionDefinition(
            name = "SUMMARIZE_URL",
            description = "Summarizes content from a URL",
            params = listOf(ParamDefinition("url", ParamType.STRING, true, "URL to summarize")),
            examples = listOf("summarize this article"),
            category = ActionCategory.INFORMATION
        ),
        ActionDefinition(
            name = "FACT_CHECK",
            description = "Fact-checks a claim",
            params = listOf(ParamDefinition("claim", ParamType.STRING, true, "Claim to verify")),
            examples = listOf("is it true that", "fact check"),
            category = ActionCategory.INFORMATION
        ),

        // ── TRANSPORT ───────────────────────────────────

        ActionDefinition(
            name = "BOOK_UBER",
            description = "Books an Uber cab",
            params = listOf(
                ParamDefinition("destination", ParamType.STRING, true, "Drop location"),
                ParamDefinition("pickup", ParamType.STRING, false, "Pickup location", defaultValue = "current location"),
                ParamDefinition("rideType", ParamType.STRING, false, "Ride type")
            ),
            examples = listOf("book uber to airport", "get cab to office"),
            category = ActionCategory.TRANSPORT
        ),
        ActionDefinition(
            name = "BOOK_OLA",
            description = "Books an Ola cab",
            params = listOf(
                ParamDefinition("destination", ParamType.STRING, true, "Drop location"),
                ParamDefinition("pickup", ParamType.STRING, false, "Pickup location", defaultValue = "current location"),
                ParamDefinition("rideType", ParamType.STRING, false, "Ride type")
            ),
            examples = listOf("book ola to station", "ola cab to mall"),
            category = ActionCategory.TRANSPORT
        ),
        ActionDefinition(
            name = "GET_DIRECTIONS",
            description = "Gets directions to a destination",
            params = listOf(
                ParamDefinition("to", ParamType.STRING, true, "Destination"),
                ParamDefinition("from", ParamType.STRING, false, "Starting point"),
                ParamDefinition("mode", ParamType.ENUM, false, "Travel mode", listOf("drive", "walk", "transit", "bike"), "drive")
            ),
            examples = listOf("directions to airport", "how to reach mall"),
            category = ActionCategory.TRANSPORT
        ),
        ActionDefinition(
            name = "CHECK_TRAFFIC",
            description = "Checks traffic conditions on a route",
            params = listOf(ParamDefinition("route", ParamType.STRING, true, "Route to check")),
            examples = listOf("traffic to office", "how is traffic"),
            category = ActionCategory.TRANSPORT
        ),
        ActionDefinition(
            name = "CHECK_FLIGHT",
            description = "Checks flight status",
            params = listOf(ParamDefinition("flightNumber", ParamType.STRING, true, "Flight number")),
            examples = listOf("check flight AI101", "flight status"),
            category = ActionCategory.TRANSPORT
        ),
        ActionDefinition(
            name = "TRACK_DELIVERY",
            description = "Tracks a delivery package",
            params = listOf(
                ParamDefinition("trackingNumber", ParamType.STRING, true, "Tracking number"),
                ParamDefinition("courier", ParamType.STRING, false, "Courier service")
            ),
            examples = listOf("track my package", "delivery status"),
            category = ActionCategory.TRANSPORT
        ),

        // ── MEDIA ───────────────────────────────────────

        ActionDefinition(
            name = "PLAY_MUSIC",
            description = "Plays music on Spotify or YouTube",
            params = listOf(
                ParamDefinition("query", ParamType.STRING, true, "Song, artist, or playlist name"),
                ParamDefinition("app", ParamType.ENUM, false, "Music app", listOf("spotify", "youtube", "local"), "spotify")
            ),
            examples = listOf("play Arijit Singh", "play Bollywood hits"),
            category = ActionCategory.MEDIA
        ),
        ActionDefinition(
            name = "PAUSE_MUSIC",
            description = "Pauses currently playing music",
            params = emptyList(),
            examples = listOf("pause music", "stop song"),
            category = ActionCategory.MEDIA
        ),
        ActionDefinition(
            name = "NEXT_TRACK",
            description = "Skips to next track",
            params = emptyList(),
            examples = listOf("next song", "skip track"),
            category = ActionCategory.MEDIA
        ),
        ActionDefinition(
            name = "PREV_TRACK",
            description = "Goes to previous track",
            params = emptyList(),
            examples = listOf("previous song", "go back"),
            category = ActionCategory.MEDIA
        ),
        ActionDefinition(
            name = "SET_VOLUME_MUSIC",
            description = "Sets music volume level",
            params = listOf(ParamDefinition("level", ParamType.STRING, true, "Volume level 0-100")),
            examples = listOf("music volume 50"),
            category = ActionCategory.MEDIA
        ),
        ActionDefinition(
            name = "PLAY_YOUTUBE",
            description = "Plays a video on YouTube",
            params = listOf(ParamDefinition("query", ParamType.STRING, true, "Video search query")),
            examples = listOf("play cat videos on youtube", "youtube funny clips"),
            category = ActionCategory.MEDIA
        ),
        ActionDefinition(
            name = "TAKE_PHOTO",
            description = "Takes a photo using camera",
            params = listOf(ParamDefinition("camera", ParamType.ENUM, false, "Camera to use", listOf("back", "front"), "back")),
            examples = listOf("take photo", "take selfie", "click pic"),
            category = ActionCategory.MEDIA
        ),
        ActionDefinition(
            name = "RECORD_VIDEO",
            description = "Records a video using camera",
            params = listOf(
                ParamDefinition("duration", ParamType.STRING, false, "Duration in seconds"),
                ParamDefinition("camera", ParamType.ENUM, false, "Camera to use", listOf("back", "front"), "back")
            ),
            examples = listOf("record video", "take a video"),
            category = ActionCategory.MEDIA
        ),
        ActionDefinition(
            name = "TAKE_PHOTO_BACKGROUND",
            description = "Takes a photo silently in the background",
            params = emptyList(),
            examples = listOf("background photo"),
            category = ActionCategory.MEDIA
        ),

        // ── SHOPPING ────────────────────────────────────

        ActionDefinition(
            name = "ORDER_FOOD",
            description = "Orders food from Zomato or Swiggy",
            params = listOf(
                ParamDefinition("items", ParamType.STRING, true, "Food items to order"),
                ParamDefinition("app", ParamType.ENUM, false, "Food delivery app", listOf("zomato", "swiggy"), "zomato"),
                ParamDefinition("address", ParamType.STRING, false, "Delivery address")
            ),
            examples = listOf("order pizza", "order food from zomato"),
            category = ActionCategory.SHOPPING,
            isSimple = false
        ),
        ActionDefinition(
            name = "ORDER_GROCERY",
            description = "Orders groceries from Blinkit, Zepto, or BigBasket",
            params = listOf(
                ParamDefinition("items", ParamType.STRING, true, "Grocery items"),
                ParamDefinition("app", ParamType.ENUM, false, "Grocery app", listOf("blinkit", "zepto", "bigbasket"), "blinkit")
            ),
            examples = listOf("order milk from blinkit", "buy groceries"),
            category = ActionCategory.SHOPPING,
            isSimple = false
        ),
        ActionDefinition(
            name = "SEARCH_AMAZON",
            description = "Searches for products on Amazon",
            params = listOf(ParamDefinition("query", ParamType.STRING, true, "Product search query")),
            examples = listOf("search headphones on amazon"),
            category = ActionCategory.SHOPPING
        ),
        ActionDefinition(
            name = "SEARCH_FLIPKART",
            description = "Searches for products on Flipkart",
            params = listOf(ParamDefinition("query", ParamType.STRING, true, "Product search query")),
            examples = listOf("search phones on flipkart"),
            category = ActionCategory.SHOPPING
        ),
        ActionDefinition(
            name = "ADD_TO_CART",
            description = "Adds a product to shopping cart",
            params = listOf(
                ParamDefinition("product", ParamType.STRING, true, "Product name"),
                ParamDefinition("app", ParamType.STRING, false, "Shopping app")
            ),
            examples = listOf("add to cart"),
            category = ActionCategory.SHOPPING
        ),

        // ── SMART_HOME ──────────────────────────────────

        ActionDefinition(
            name = "SMART_HOME",
            description = "Controls a smart home device",
            params = listOf(
                ParamDefinition("device", ParamType.STRING, true, "Device name"),
                ParamDefinition("action", ParamType.STRING, true, "Action to perform"),
                ParamDefinition("value", ParamType.STRING, false, "Value parameter")
            ),
            examples = listOf("turn on living room fan", "smart home"),
            category = ActionCategory.SMART_HOME
        ),
        ActionDefinition(
            name = "TOGGLE_LIGHT",
            description = "Toggles a smart light on or off",
            params = listOf(
                ParamDefinition("room", ParamType.STRING, true, "Room name"),
                ParamDefinition("on", ParamType.STRING, false, "on or off", defaultValue = "true"),
                ParamDefinition("brightness", ParamType.STRING, false, "Brightness level"),
                ParamDefinition("color", ParamType.STRING, false, "Light color")
            ),
            examples = listOf("turn on bedroom light", "dim living room"),
            category = ActionCategory.SMART_HOME
        ),
        ActionDefinition(
            name = "SET_THERMOSTAT",
            description = "Sets thermostat temperature",
            params = listOf(ParamDefinition("temperature", ParamType.STRING, true, "Temperature to set")),
            examples = listOf("set thermostat to 22", "AC to 24 degrees"),
            category = ActionCategory.SMART_HOME
        ),
        ActionDefinition(
            name = "LOCK_DOOR",
            description = "Locks a smart door lock",
            params = listOf(ParamDefinition("location", ParamType.STRING, false, "Door location", defaultValue = "front door")),
            examples = listOf("lock the front door", "lock door"),
            category = ActionCategory.SMART_HOME
        ),

        // ── FINANCE ─────────────────────────────────────

        ActionDefinition(
            name = "PAY_UPI",
            description = "Makes UPI payment via GPay/PhonePe/Paytm",
            params = listOf(
                ParamDefinition("to", ParamType.STRING, true, "Recipient UPI ID or contact"),
                ParamDefinition("amount", ParamType.STRING, true, "Amount to pay"),
                ParamDefinition("note", ParamType.STRING, false, "Payment note", defaultValue = ""),
                ParamDefinition("app", ParamType.ENUM, false, "Payment app", listOf("gpay", "phonepe", "paytm"), "gpay")
            ),
            examples = listOf("pay 500 to John", "send 200 rupees to dad"),
            category = ActionCategory.FINANCE,
            isSimple = false
        ),
        ActionDefinition(
            name = "CHECK_BALANCE",
            description = "Checks account balance",
            params = emptyList(),
            examples = listOf("check my balance", "bank balance"),
            category = ActionCategory.FINANCE
        ),
        ActionDefinition(
            name = "SPLIT_BILL",
            description = "Splits a bill among people",
            params = listOf(
                ParamDefinition("totalAmount", ParamType.STRING, true, "Total bill amount"),
                ParamDefinition("people", ParamType.STRING, true, "Number of people or names"),
                ParamDefinition("description", ParamType.STRING, false, "Bill description")
            ),
            examples = listOf("split bill 1000 among 4", "split dinner bill"),
            category = ActionCategory.FINANCE
        ),

        // ── MACRO ───────────────────────────────────────

        ActionDefinition(
            name = "RUN_MACRO",
            description = "Runs a saved macro (sequence of actions)",
            params = listOf(ParamDefinition("macroName", ParamType.STRING, true, "Macro name")),
            examples = listOf("run morning routine", "execute macro"),
            category = ActionCategory.MACRO
        ),
        ActionDefinition(
            name = "CREATE_MACRO",
            description = "Creates a new macro",
            params = listOf(
                ParamDefinition("name", ParamType.STRING, true, "Macro name"),
                ParamDefinition("steps", ParamType.STRING, true, "JSON steps definition")
            ),
            examples = listOf("create morning routine macro"),
            category = ActionCategory.MACRO,
            isSimple = false
        ),
        ActionDefinition(
            name = "SCHEDULE_MACRO",
            description = "Schedules a macro to run at specific times",
            params = listOf(
                ParamDefinition("macroName", ParamType.STRING, true, "Macro name"),
                ParamDefinition("cronExpression", ParamType.STRING, true, "Cron schedule expression")
            ),
            examples = listOf("schedule morning routine at 7am daily"),
            category = ActionCategory.MACRO,
            isSimple = false
        ),

        // ── ADVANCED (Files & Accessibility) ────────────

        ActionDefinition(
            name = "LIST_FILES",
            description = "Lists files in a directory",
            params = listOf(ParamDefinition("path", ParamType.STRING, true, "Directory path")),
            examples = listOf("list files in downloads", "show files"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "READ_FILE",
            description = "Reads contents of a file",
            params = listOf(ParamDefinition("filePath", ParamType.STRING, true, "File path")),
            examples = listOf("read file", "open document"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "WRITE_FILE",
            description = "Writes content to a file",
            params = listOf(
                ParamDefinition("filePath", ParamType.STRING, true, "File path"),
                ParamDefinition("content", ParamType.STRING, true, "Content to write")
            ),
            examples = listOf("write to file", "save text"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "DELETE_FILE",
            description = "Deletes a file",
            params = listOf(ParamDefinition("filePath", ParamType.STRING, true, "File path to delete")),
            examples = listOf("delete file"),
            category = ActionCategory.ADVANCED,
            isSimple = false
        ),
        ActionDefinition(
            name = "CREATE_DIRECTORY",
            description = "Creates a new directory",
            params = listOf(ParamDefinition("path", ParamType.STRING, true, "Directory path")),
            examples = listOf("create folder"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "COPY_FILE",
            description = "Copies a file to another location",
            params = listOf(
                ParamDefinition("sourcePath", ParamType.STRING, true, "Source file path"),
                ParamDefinition("destPath", ParamType.STRING, true, "Destination path")
            ),
            examples = listOf("copy file"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "MOVE_FILE",
            description = "Moves a file to another location",
            params = listOf(
                ParamDefinition("sourcePath", ParamType.STRING, true, "Source file path"),
                ParamDefinition("destPath", ParamType.STRING, true, "Destination path")
            ),
            examples = listOf("move file"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "ZIP_FILES",
            description = "Compresses files into a ZIP archive",
            params = listOf(
                ParamDefinition("sourcePath", ParamType.STRING, true, "Source path"),
                ParamDefinition("zipFilePath", ParamType.STRING, true, "Output ZIP path")
            ),
            examples = listOf("zip files", "compress folder"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "UNZIP_FILE",
            description = "Extracts a ZIP archive",
            params = listOf(
                ParamDefinition("zipFilePath", ParamType.STRING, true, "ZIP file path"),
                ParamDefinition("destDirPath", ParamType.STRING, true, "Destination directory")
            ),
            examples = listOf("unzip file", "extract archive"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "LIST_INSTALLED_APPS",
            description = "Lists all installed apps on the device",
            params = emptyList(),
            examples = listOf("list apps", "show installed apps"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "CLICK_TEXT",
            description = "Clicks on screen element by visible text",
            params = listOf(ParamDefinition("text", ParamType.STRING, true, "Text to click on")),
            examples = listOf("click on Settings"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "CLICK_ID",
            description = "Clicks on screen element by view ID",
            params = listOf(ParamDefinition("viewId", ParamType.STRING, true, "View ID to click")),
            examples = listOf("click button with id"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "TYPE_TEXT",
            description = "Types text into a field found by search text",
            params = listOf(
                ParamDefinition("searchText", ParamType.STRING, true, "Text to find the field"),
                ParamDefinition("content", ParamType.STRING, true, "Content to type")
            ),
            examples = listOf("type in search box"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "TYPE_ID",
            description = "Types text into a field found by view ID",
            params = listOf(
                ParamDefinition("viewId", ParamType.STRING, true, "View ID of the field"),
                ParamDefinition("content", ParamType.STRING, true, "Content to type")
            ),
            examples = listOf("type in field"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "SCROLL",
            description = "Scrolls the screen",
            params = listOf(ParamDefinition("direction", ParamType.ENUM, true, "Scroll direction", listOf("forward", "backward"))),
            examples = listOf("scroll down", "scroll up"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "GET_SCREEN_TEXT",
            description = "Reads all visible text on screen",
            params = emptyList(),
            examples = listOf("read screen text", "get screen content"),
            category = ActionCategory.ADVANCED
        ),
        ActionDefinition(
            name = "CLICK_COORDINATES",
            description = "Clicks at specific screen coordinates",
            params = listOf(
                ParamDefinition("x", ParamType.STRING, true, "X coordinate"),
                ParamDefinition("y", ParamType.STRING, true, "Y coordinate")
            ),
            examples = listOf("click at position"),
            category = ActionCategory.ADVANCED
        ),

        // ── AGENT ───────────────────────────────────────

        ActionDefinition(
            name = "ASK_USER",
            description = "Asks user for missing information needed to complete task",
            params = listOf(
                ParamDefinition("question", ParamType.STRING, true, "Question to ask user"),
                ParamDefinition("options", ParamType.STRING, false, "Comma-separated options", defaultValue = "")
            ),
            examples = emptyList(),
            category = ActionCategory.AGENT
        ),
        ActionDefinition(
            name = "CHAT",
            description = "Responds conversationally without device action",
            params = listOf(ParamDefinition("response", ParamType.STRING, true, "Conversational response")),
            examples = listOf("how are you", "tell me a joke", "what can you do"),
            category = ActionCategory.AGENT
        )
    )

    // ── Utility functions ───────────────────────────────

    /** Get all action names — inject into LLM prompt */
    fun getAllActionNames(): List<String> =
        ALL_ACTIONS.map { it.name }.sorted()

    /** Get action by name */
    fun getAction(name: String): ActionDefinition? =
        ALL_ACTIONS.find { it.name == name }

    /** Check if action exists in schema */
    fun isValid(name: String): Boolean =
        ALL_ACTIONS.any { it.name == name }

    /** Get actions by category */
    fun getByCategory(category: ActionCategory): List<ActionDefinition> =
        ALL_ACTIONS.filter { it.category == category }

    /** Get simple actions (never need a plan) */
    fun getSimpleActions(): List<String> =
        ALL_ACTIONS.filter { it.isSimple }.map { it.name }

    /**
     * Build strict schema text for LLM prompt.
     * This is the key function — LLM gets typed schema and cannot invent actions.
     */
    fun buildLLMSchema(): String {
        val sb = StringBuilder()
        sb.appendLine("AVAILABLE ACTIONS:")
        sb.appendLine("You MUST pick from ONLY these actions. Any action NOT in this list is INVALID.")
        sb.appendLine()

        ActionCategory.values().forEach { category ->
            val categoryActions = ALL_ACTIONS.filter { it.category == category }
            if (categoryActions.isEmpty()) return@forEach

            sb.appendLine("── ${category.name} ──")

            categoryActions.forEach { action ->
                sb.appendLine("${action.name}:")
                sb.appendLine("  What: ${action.description}")

                if (action.params.isNotEmpty()) {
                    sb.appendLine("  Params:")
                    action.params.forEach { param ->
                        val req = if (param.required) "REQUIRED" else "optional"
                        val enumStr = if (param.enumValues.isNotEmpty())
                            " [${param.enumValues.joinToString("|")}]" else ""
                        val defStr = if (param.defaultValue != null)
                            " (default: ${param.defaultValue})" else ""
                        sb.appendLine("    - ${param.name} ($req)$enumStr: ${param.description}$defStr")
                    }
                } else {
                    sb.appendLine("  Params: none")
                }

                if (action.examples.isNotEmpty()) {
                    sb.appendLine("  Triggers: ${action.examples.take(3).joinToString(", ")}")
                }
                sb.appendLine()
            }
        }

        sb.appendLine("TOTAL: ${ALL_ACTIONS.size} actions available.")
        sb.appendLine("ANY action NOT in this list = INVALID. Use CHAT for unsupported requests.")
        return sb.toString()
    }

    /**
     * Build compact schema for PlanningPrompts (grouped by category with params).
     */
    fun buildPlanningSchema(): String {
        val sb = StringBuilder()
        var categoryIndex = 1

        ActionCategory.values().forEach { category ->
            val categoryActions = ALL_ACTIONS.filter { it.category == category }
            if (categoryActions.isEmpty()) return@forEach

            sb.appendLine("$categoryIndex. ${category.name}")
            categoryActions.forEach { action ->
                val paramStr = if (action.params.isEmpty()) "{}"
                else "{${action.params.joinToString(", ") { p ->
                    val enumHint = if (p.enumValues.isNotEmpty()) " (${p.enumValues.joinToString("|")})" else ""
                    "${p.name}: String$enumHint"
                }}}"
                sb.appendLine("   - ${action.name} $paramStr")
            }
            sb.appendLine()
            categoryIndex++
        }

        return sb.toString()
    }

    /**
     * Validate LLM response params against schema.
     * Returns both validation result AND params with defaults applied.
     */
    fun validateParams(
        actionName: String,
        params: Map<String, Any>
    ): Pair<ValidationResult, Map<String, Any>> {
        val definition = getAction(actionName)
            ?: return Pair(ValidationResult.InvalidAction(actionName), params)

        val enrichedParams = params.toMutableMap()
        val missingRequired = mutableListOf<String>()

        definition.params.forEach { paramDef ->
            val value = enrichedParams[paramDef.name]

            when {
                // Param provided — validate enum if needed
                value != null -> {
                    if (paramDef.type == ParamType.ENUM &&
                        paramDef.enumValues.isNotEmpty()) {
                        val strValue = value.toString().lowercase()
                        val validValues = paramDef.enumValues.map { it.lowercase() }
                        if (!validValues.contains(strValue)) {
                            val fixed = fixEnumValue(strValue, paramDef.enumValues)
                            if (fixed != null) {
                                enrichedParams[paramDef.name] = fixed
                            } else {
                                missingRequired.add(paramDef.name)
                            }
                        }
                    }
                }

                // Param missing but has default — apply default
                paramDef.defaultValue != null -> {
                    enrichedParams[paramDef.name] = paramDef.defaultValue
                }

                // Param missing, no default, required — error
                paramDef.required -> {
                    missingRequired.add(paramDef.name)
                }

                // Param missing, not required, no default — ok
                else -> { /* skip */ }
            }
        }

        return if (missingRequired.isEmpty()) {
            Pair(ValidationResult.Valid, enrichedParams)
        } else {
            Pair(ValidationResult.MissingParams(missingRequired), enrichedParams)
        }
    }

    /** Fix minor enum value variations */
    private fun fixEnumValue(input: String, validValues: List<String>): String? {
        // Exact match (case-insensitive)
        validValues.find { it.lowercase() == input.lowercase() }?.let { return it }

        // Common synonyms
        val synonyms = mapOf(
            "yes" to "on", "enable" to "on", "activate" to "on",
            "true" to "on", "start" to "on",
            "no" to "off", "disable" to "off", "deactivate" to "off",
            "false" to "off", "stop" to "off",
            "switch" to "toggle", "flip" to "toggle", "change" to "toggle"
        )

        val synonym = synonyms[input.lowercase()]
        return validValues.find { it.lowercase() == synonym?.lowercase() }
    }

    /** Apply default values for missing params */
    fun applyDefaults(actionName: String, params: Map<String, Any>): Map<String, Any> {
        val definition = getAction(actionName) ?: return params
        val result = params.toMutableMap()
        definition.params.forEach { paramDef ->
            if (!result.containsKey(paramDef.name) && paramDef.defaultValue != null) {
                result[paramDef.name] = paramDef.defaultValue
            }
        }
        return result
    }

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class InvalidAction(val name: String) : ValidationResult()
        data class MissingParams(val params: List<String>) : ValidationResult()
    }
}
