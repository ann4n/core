package org.simpmusic.aiservice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VocalRemoverTest {
    private fun done(files: String) = Json.parseToJsonElement("""{"success":true,"status":"done","data":{"files":$files}}""").jsonObject

    @Test
    fun picksTheStemThatIsNotVocals() {
        // Shape of a real finished job's response (fields trimmed).
        val json = done("""[{"type":"Vocals","url":"https://x/v.m4a"},{"type":"Other","url":"https://x/o.m4a"}]""")
        assertEquals("https://x/o.m4a", VocalRemover.instrumentalUrl(json))
    }

    @Test
    fun noInstrumentalIsAnError() {
        assertFailsWith<VocalRemoverException> { VocalRemover.instrumentalUrl(done("""[{"type":"Vocals","url":"https://x/v.m4a"}]""")) }
    }
}
