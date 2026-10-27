package com.pntapps.pocketserver.catalog

/**
 * One-click apps. Each id matches a folder in assets/catalog holding install.sh and start.sh,
 * which carry the battery-optimized settings for that app.
 */
data class CatalogApp(
    val id: String,
    val name: String,
    val tagline: String,
    val port: Int,
    val https: Boolean = false,
    /** Rough download + install size, shown before installing. */
    val sizeMb: Int,
    /** Below this much device RAM the app is shown as "Not recommended on this device". */
    val minRamMb: Int,
    /** Approximate resident memory while running, for the dashboard. */
    val typicalRamMb: Int,
    val notes: List<String> = emptyList(),
    /** Plain-HTTP path that answers below 500 once the app is really ready (null: port open is enough). */
    val healthPath: String? = "/",
    /** If set, the health response body must contain this too (Jellyfin answers 200 "Degraded" while booting). */
    val healthText: String? = null,
    /** Plain-HTTP port bound to 127.0.0.1, used by Open on this device (localhost is a secure context, no certificate needed). */
    val localPort: Int? = null,
    /** Shown together under one heading on the Apps screen, with an "install all" action. */
    val group: String? = null,
    /** Apps installed first, automatically (e.g. the Dev Toolchain). */
    val requires: List<String> = emptyList(),
    /** False for install-only items (tools, no process to start). */
    val service: Boolean = true,
    /** Not a website (databases): no Open button or QR code. */
    val localOnly: Boolean = false,
    /** Offers a "Wi-Fi access" switch: listen on the local network (login always required), off by default. */
    val lanToggle: Boolean = false,
) {
    fun url(host: String) = "${if (https) "https" else "http"}://$host:$port"

    /** Address this device's own browser should open. */
    val localUrl get() = localPort?.let { "http://127.0.0.1:$it" } ?: url("127.0.0.1")
}

object Catalog {
    val apps = listOf(
        CatalogApp(
            id = "filebrowser",
            group = FILES,
            name = "FileBrowser",
            tagline = "Your files from any browser on your Wi-Fi",
            port = 8080, sizeMb = 30, minRamMb = 1024, typicalRamMb = 70,
            notes = listOf(
                "FileBrowser Quantum: the maintained fork, with regular security fixes",
                "Thumbnails, previews and update checks off; no shell commands",
                "WebDAV for other apps, sign-in always required",
            ),
        ),
        CatalogApp(
            id = "syncthing",
            group = FILES,
            name = "Syncthing",
            tagline = "Sync folders between your devices",
            port = 8384, sizeMb = 30, minRamMb = 1024, typicalRamMb = 40,
            notes = listOf("Local network only: relays, global discovery and NAT off", "Usage reporting and self-update off"),
        ),
        CatalogApp(
            id = "jellyfin",
            group = MEDIA,
            name = "Jellyfin",
            tagline = "Stream your movies, shows and music",
            port = 8096, sizeMb = 450, minRamMb = 3072, typicalRamMb = 250, healthPath = "/health", healthText = "Healthy",
            notes = listOf(
                "Transcoding disabled for every user (direct play and remux only)",
                "No trickplay, chapter images or loudness scans",
                "Library scans only while charging",
            ),
        ),
        CatalogApp(
            id = "vaultwarden",
            group = PRODUCTIVITY,
            name = "Vaultwarden",
            tagline = "Password manager for Bitwarden apps",
            port = 8443, https = true, sizeMb = 70, minRamMb = 1024, typicalRamMb = 25, healthPath = null,
            notes = listOf(
                "Sign-ups never open: only the email you enter here can create the first account",
                "Install PocketServer-CA.crt from the shared folder on each device",
                "Data is deleted if you uninstall PocketServer: back up first",
            ),
        ),
        CatalogApp(
            id = "bentopdf",
            group = PRODUCTIVITY,
            name = "BentoPDF",
            tagline = "Merge, split, compress, sign and convert PDFs",
            port = 8444, https = true, localPort = 8085, sizeMb = 300, minRamMb = 1024, typicalRamMb = 10, healthPath = null,
            notes = listOf(
                "PDFs are processed in your browser and never uploaded",
                "Tiny server: files pre-compressed, nothing computed on this device",
                "OCR and a few engines download from the internet on first use",
                "Other devices: install PocketServer-CA.crt once",
            ),
        ),
        CatalogApp(
            id = "nextcloud",
            name = "Nextcloud",
            tagline = "Your own cloud for files and photos",
            port = 8446, https = true, localPort = 8087, sizeMb = 1100, minRamMb = 3072, typicalRamMb = 120,
            healthPath = "/status.php", group = FILES,
            notes = listOf(
                "Files, photos and WebDAV for your devices (SQLite, single user)",
                "PHP runs only during requests; no update checks or news feeds",
                "Calendar and Contacts: add from its Apps page (internet once)",
            ),
        ),
        CatalogApp(
            id = "memos",
            name = "Memos",
            tagline = "Quick notes and journal",
            port = 5230, sizeMb = 65, minRamMb = 1024, typicalRamMb = 45, group = PRODUCTIVITY,
            notes = listOf(
                "Markdown notes with tags",
                "Sign-ups closed after the admin account",
                "Tiny single process, about 45 MB RAM",
            ),
        ),
        CatalogApp(
            id = "navidrome",
            name = "Navidrome",
            tagline = "Your music, streamed like Spotify",
            port = 4533, sizeMb = 70, minRamMb = 1024, typicalRamMb = 40, group = MEDIA,
            notes = listOf(
                "Streams /shared/Music to the web player and Subsonic apps",
                "No transcoding: plays files as they are",
                "Library scan once a day, no scan at start",
            ),
        ),
        CatalogApp(
            id = "audiobookshelf",
            name = "Audiobookshelf",
            tagline = "Audiobooks and podcasts with progress sync",
            port = 13378, sizeMb = 290, minRamMb = 2048, typicalRamMb = 130, healthPath = "/healthcheck", group = MEDIA,
            notes = listOf(
                "Libraries in /shared/Audiobooks and /shared/Podcasts",
                "No timed scans, folder watching or online cover lookups",
                "Podcast auto-download stays off unless you turn it on for a podcast",
            ),
        ),
        CatalogApp(
            id = "calibreweb",
            name = "Calibre-Web",
            tagline = "E-book library you can read in the browser",
            port = 8083, sizeMb = 195, minRamMb = 2048, typicalRamMb = 125, healthPath = "/login", group = MEDIA,
            notes = listOf(
                "Library in /shared/Books; upload books from the browser",
                "No Calibre needed (no format conversion)",
                "Takes about 25 s to start",
            ),
        ),
        CatalogApp(
            id = "qbittorrent",
            name = "qBittorrent",
            tagline = "Torrent client with a web interface",
            port = 8180, sizeMb = 35, minRamMb = 1024, typicalRamMb = 30, group = DOWNLOADS,
            notes = listOf(
                "Local network only: UPnP and NAT-PMP off",
                "Saves to /shared/Downloads; stops seeding at ratio 1",
                "Slower on battery, full speed while charging",
            ),
        ),
        CatalogApp(
            id = "prowlarr",
            name = "Prowlarr",
            tagline = "Indexer manager for Sonarr and Radarr",
            port = 9696, sizeMb = 150, minRamMb = 2048, typicalRamMb = 170, group = DOWNLOADS,
            notes = listOf(
                "Add your indexers once here",
                "Syncs them to Sonarr and Radarr automatically",
                "Analytics and self-updates off",
            ),
        ),
        CatalogApp(
            id = "sonarr",
            name = "Sonarr",
            tagline = "TV show library manager",
            port = 8989, sizeMb = 165, minRamMb = 2048, typicalRamMb = 150, group = DOWNLOADS,
            notes = listOf(
                "Library in /shared/Shows, which Jellyfin already reads",
                "Checks for new episodes every 2 hours, not every 15 minutes",
                "Linked to qBittorrent and Prowlarr when installed",
            ),
        ),
        CatalogApp(
            id = "radarr",
            name = "Radarr",
            tagline = "Movie library manager",
            port = 7878, sizeMb = 170, minRamMb = 2048, typicalRamMb = 170, group = DOWNLOADS,
            notes = listOf(
                "Library in /shared/Movies, which Jellyfin already reads",
                "Checks for wanted movies every 2 hours",
                "Linked to qBittorrent and Prowlarr when installed",
            ),
        ),
        CatalogApp(
            id = "devtools",
            name = "Dev Toolchain",
            tagline = "Node.js, PHP, Python, Git and build tools",
            port = 0, sizeMb = 700, minRamMb = 2048, typicalRamMb = 0, service = false, group = WEB_DEV,
            notes = listOf(
                "Node.js LTS + npm, PHP 8.3 + Composer, Python 3 + pip, Git, mongosh",
                "Use them from the Shell tab or the Code Studio terminal",
                "Update checks and telemetry turned off",
            ),
        ),
        CatalogApp(
            id = "pyweb",
            name = "Django & Flask",
            tagline = "Python web frameworks with starter projects",
            port = 0, sizeMb = 110, minRamMb = 2048, typicalRamMb = 0, service = false,
            group = WEB_DEV, requires = listOf("devtools"),
            notes = listOf(
                "Django, Flask and Gunicorn in a shared Python environment: pip install just works",
                "MySQL (mysqlclient, PyMySQL) and MongoDB (pymongo) drivers included",
                "Starters in /root/projects; run them from Code Studio or the Shell tab",
            ),
        ),
        CatalogApp(
            id = "laravel",
            name = "Laravel",
            tagline = "PHP framework with a ready starter app",
            port = 0, sizeMb = 120, minRamMb = 2048, typicalRamMb = 0, service = false,
            group = WEB_DEV, requires = listOf("devtools"),
            notes = listOf(
                "Laravel installer (laravel new) and a starter in /root/projects/laravel-starter",
                "Starter uses SQLite, so it runs without any database setup",
                "Switch it to MariaDB in .env when you need MySQL",
            ),
        ),
        CatalogApp(
            id = "react",
            name = "React",
            tagline = "React + Vite starter, ready to run",
            port = 0, sizeMb = 90, minRamMb = 2048, typicalRamMb = 0, service = false,
            group = WEB_DEV, requires = listOf("devtools"),
            notes = listOf(
                "Starter in /root/projects/react-starter with dependencies installed",
                "Dev server listens on the Wi-Fi too, so phones can open it",
                "npm run build for a production bundle",
            ),
        ),
        CatalogApp(
            id = "codestudio",
            name = "Code Studio",
            tagline = "VS Code in your browser, with terminal",
            port = 8090, https = true, localPort = 8091, sizeMb = 680, minRamMb = 3072, typicalRamMb = 250,
            healthPath = "/login", group = WEB_DEV, requires = listOf("devtools"),
            notes = listOf(
                "Telemetry, update checks and workspace trust prompts off",
                "Projects live in /root/projects",
                "Preview dev servers at /proxy/<port>/ (e.g. npm run dev)",
            ),
        ),
        CatalogApp(
            id = "gitea",
            name = "Gitea",
            tagline = "Your own GitHub: repos, issues, pull requests",
            port = 3300, sizeMb = 120, minRamMb = 2048, typicalRamMb = 130, healthPath = "/api/v1/version", group = WEB_DEV,
            notes = listOf(
                "Git over HTTP and SSH (port 2222), with LFS",
                "Sign-ups closed; update checks, mail and indexers off",
                "Add your SSH key under Settings > SSH keys",
            ),
        ),
        CatalogApp(
            id = "phpsites",
            name = "PHP Sites",
            tagline = "nginx + PHP-FPM for /root/www, with Adminer",
            port = 8088, sizeMb = 20, minRamMb = 1024, typicalRamMb = 50, group = WEB_DEV, requires = listOf("devtools"),
            notes = listOf(
                "PHP workers start on demand and exit after 10 s idle",
                "Manage databases at /adminer/",
                "OPcache on, access logs off",
            ),
        ),
        CatalogApp(
            id = "wordpress",
            name = "WordPress",
            tagline = "Websites and blogs, set up and ready to edit",
            port = 8448, https = true, localPort = 8089, sizeMb = 140, minRamMb = 2048, typicalRamMb = 60,
            healthPath = "/wp-login.php", group = WEB_DEV,
            notes = listOf(
                "Ready to use: admin account made, pretty links on, sign-ups closed",
                "SQLite database: no MariaDB has to run alongside it",
                "PHP runs only during requests; no automatic updates or update pings",
            ),
        ),
        CatalogApp(
            id = "mariadb",
            name = "MariaDB",
            tagline = "MySQL-compatible database",
            port = 3306, sizeMb = 60, minRamMb = 2048, typicalRamMb = 55, healthPath = null,
            localOnly = true, lanToggle = true, group = WEB_DEV, requires = listOf("devtools"),
            notes = listOf(
                "Small memory profile (64 MB buffer pool), no binary logs",
                "This device only unless you switch on Wi-Fi access; password always required",
            ),
        ),
        CatalogApp(
            id = "mongodb",
            name = "MongoDB",
            tagline = "Document database (MongoDB 7.0)",
            port = 27017, sizeMb = 200, minRamMb = 3072, typicalRamMb = 130, healthPath = null,
            localOnly = true, lanToggle = true, group = WEB_DEV, requires = listOf("devtools"),
            notes = listOf(
                "256 MB cache, diagnostics collection off, login always required",
                "Version 7.0: MongoDB 8 needs memory features Android does not offer",
                "This device only unless you switch on Wi-Fi access",
            ),
        ),
    )

    const val MEDIA = "Media Pack"
    const val FILES = "Files & Cloud Pack"
    const val DOWNLOADS = "Downloads Pack"
    const val PRODUCTIVITY = "Productivity Pack"
    const val WEB_DEV = "Web Dev Pack"

    /** Packs in screen order, each with a one-line pitch for the user it serves. */
    val packs = linkedMapOf(
        MEDIA to "Watch, listen and read: movies and shows, your music, audiobooks and e-books, streamed to any device on your Wi-Fi.",
        FILES to "Your own private cloud: browse files from any browser, sync folders between devices, and Nextcloud for files and calendars.",
        DOWNLOADS to "Media automation: a torrent client plus apps that find and organize TV shows and movies straight into your Media Pack folders.",
        PRODUCTIVITY to "Everyday tools: a password manager for Bitwarden apps, PDF tools that never upload your files, and quick notes.",
        WEB_DEV to "Full-stack web development on this device: code in the browser, run Node.js, PHP or Python, and use MySQL (MariaDB) or MongoDB. Start only the parts you need; each stops on its own like any other app.",
    )

    fun byId(id: String) = apps.first { it.id == id }

    val services get() = apps.filter { it.service }
}
