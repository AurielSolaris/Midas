package app.auriel.midas.desktop

import app.auriel.midas.data.SettingsStorage
import java.io.File

object AppInfo {
    const val NAME = "Midas"
    const val VERSION = "0.1.0"
}

enum class OperatingSystem {
    Windows, Mac, Linux;

    companion object {
        val current: OperatingSystem = System.getProperty("os.name").lowercase().let {
            when {
                "win" in it -> Windows
                "mac" in it || "darwin" in it -> Mac
                else -> Linux
            }
        }
    }
}

/** Per-OS config folder: %APPDATA%\Midas, ~/Library/Application Support/Midas, ~/.config/midas. */
object AppDirs {
    private val home = File(System.getProperty("user.home"))

    val config: File by lazy {
        when (OperatingSystem.current) {
            OperatingSystem.Windows -> File(System.getenv("APPDATA") ?: home.path, "Midas")
            OperatingSystem.Mac -> File(home, "Library/Application Support/Midas")
            OperatingSystem.Linux -> File(System.getenv("XDG_CONFIG_HOME") ?: File(home, ".config").path, "midas")
        }.apply { mkdirs() }
    }
}

/** Settings as a JSON file in the config folder, written atomically via a temp file. */
class FileSettingsStorage(private val file: File = File(AppDirs.config, "settings.json")) : SettingsStorage {
    override fun read(): String? = file.takeIf { it.exists() }?.readText()

    override fun write(contents: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(contents)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
