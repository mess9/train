package train.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerInfoTest {
    @Test
    fun unknownFieldsAreIgnored() {
        val info = TrainJson.decodeFromString<ServerInfo>("""{"name":"train","version":"1","extra":true}""")
        assertEquals(ServerInfo("train", "1"), info)
    }
}
