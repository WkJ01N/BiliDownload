package io.github.bilidownload

object FileNaming {
    const val DEFAULT = "{name}"
    val variables = listOf("name", "title", "part", "index", "bvid", "id")
    private val token = Regex("\\{([^{}]+)\\}")
    fun validate(template: String) {
        require(template.isNotBlank()) { "文件名模板不能为空。" }
        val rest = token.replace(template) { require(it.groupValues[1] in variables) { "未知的文件名变量：${it.value}" }; "" }
        require('{' !in rest && '}' !in rest) { "文件名模板的花括号不完整。" }
    }
    fun render(item: MediaItem, template: String = DEFAULT, custom: String? = null): String {
        if(custom != null) return normalize(custom)
        validate(template)
        val part = item.partTitle.trim()
        val name = if(item.partCount > 1 || item.kind != "video") "${item.title} P${item.index}" + if(part.isBlank() || part == item.title.trim()) "" else " $part" else item.title
        val values = mapOf("name" to name, "title" to item.title, "part" to item.partTitle, "index" to item.index.toString(), "bvid" to item.bvid, "id" to item.id)
        return normalize(token.replace(template) { values.getValue(it.groupValues[1]) })
    }
    fun normalize(input: String): String {
        var value = input.trim().replace(Regex("(?i)\\.mp4$"), "")
        value = value.replace(Regex("[\\p{Cc}<>:\"/\\\\|?*]"), "_").trim().trimEnd('.', ' ')
        value = String(value.codePoints().limit(100).toArray(), 0, value.codePointCount(0, value.length).coerceAtMost(100)).trimEnd('.', ' ')
        if(value.isBlank()) value = "video"
        if(Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$").matches(value.substringBefore('.'))) value = "_$value"
        return "$value.mp4"
    }
    fun candidate(name: String, n: Int) = if(n == 0) name else "${name.removeSuffix(".mp4")} ($n).mp4"
}
