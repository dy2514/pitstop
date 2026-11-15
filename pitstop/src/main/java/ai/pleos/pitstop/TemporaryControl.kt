package ai.pleos.pitstop


typealias Settings = Map<String, String>

interface TemporaryControl {
    val id: String
    val title: String
    val description: String
    suspend fun read(): Settings
    fun target(before: Settings): Settings
    suspend fun write(values: Settings, expectedBefore: Settings)
    suspend fun writeTracked(values: Settings, expectedBefore: Settings, confirmed: (Settings) -> Unit) {
        write(values, expectedBefore)
        confirmed(values)
    }
    fun restoredMatches(before: Settings, current: Settings): Boolean = before == current
    fun format(values: Settings): String
}

class IntegerControl(
    override val id: String,
    override val title: String,
    override val description: String,
    private val get: suspend () -> Int,
    private val set: suspend (Int) -> Unit,
    private val choose: (Int) -> Int,
    private val display: (Int) -> String = { "$it 단계" },
) : TemporaryControl {
    override suspend fun read(): Settings = mapOf("value" to get().toString())
    override fun target(before: Settings): Settings = mapOf("value" to choose(before.getValue("value").toInt()).toString())
    override suspend fun write(values: Settings, expectedBefore: Settings) {
        check(read() == expectedBefore) { "직접 바뀐 설정을 유지해요." }
        set(values.getValue("value").toInt())
    }
    override fun format(values: Settings) = display(values.getValue("value").toInt())
}