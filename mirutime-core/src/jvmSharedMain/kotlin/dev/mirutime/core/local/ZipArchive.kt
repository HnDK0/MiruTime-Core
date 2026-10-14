package dev.mirutime.core.local

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

// Верхний предел размера одной распакованной записи (port из NoveLA
// EpubParser.readEntry: крафтовый архив с огромной записью вёл к OOM).
private const val MAX_ENTRY_SIZE = 32L * 1024 * 1024

// Суммарный бюджет прочитанных записей (NoveLA MAX_TOTAL_IMAGE_BYTES):
// per-entry лимит не спасает от сотен мегабайт множеством записей.
private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

// Читает весь zip в map "имя записи → байты" одним проходом ZipInputStream.
// ceiling: архив целиком в памяти — FileSystem.read и так отдаёт целиковые
// байты файла, а NoveLA-вариант с temp-файлом + ZipFile ради книг в 7k глав
// нужен только после профилирования импорта больших книг.
internal fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
    val out = linkedMapOf<String, ByteArray>()
    var total = 0L
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                val data = readEntryLimited(zip)
                total += data.size
                if (total > MAX_TOTAL_BYTES) throw IllegalStateException("zip total too large")
                out[entry.name] = data
            }
            entry = zip.nextEntry
        }
    }
    return out
}

// Чтение с верхним пределом: файл-переросток отклоняем вместо OOM.
internal fun readEntryLimited(zip: ZipInputStream): ByteArray {
    val buffer = ByteArray(64 * 1024)
    val out = ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val n = zip.read(buffer)
        if (n < 0) break
        total += n
        if (total > MAX_ENTRY_SIZE) throw IllegalStateException("zip entry too large")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

// FB2 встречается zip-обёрнутым (".fb2.zip"): если это zip — берём первое
// содержимое, иначе сырые байты (порт из NoveLA Fb2Parser.readFb2Data).
internal fun unwrapSingleEntryZip(raw: ByteArray): ByteArray = try {
    ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
        val entry = zip.nextEntry
        if (entry != null && !entry.isDirectory) readEntryLimited(zip) else raw
    }
} catch (_: Exception) {
    raw
}
