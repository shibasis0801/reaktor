package dev.shibasis.flatinvoker.core

import dev.shibasis.flatinvoker.core.ListSerializer.decodeFromList
import dev.shibasis.flatinvoker.core.ListSerializer.encodeToList
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals


@Serializable
data class Project(val name: String, val owners: List<User>, val test: HashMap<String, User>, val votes: Int?)

@Serializable
data class User(val name: String)

class FlexBufferEncodeDecodeTests {
    @Test
    fun testEncodeDecode() {
        val data = Project("kotlinx.serialization",  listOf(User("kotlin"), User("jetbrains")), hashMapOf("shibasis" to User("patnaik")), null)
        val buffer = encodeToList(data)
        val obj = decodeFromList<Project>(buffer)
        assertEquals(obj.name, data.name)
        assertEquals(obj.votes, data.votes)
        assertEquals(obj.owners.size, data.owners.size)
        assertEquals(obj.owners[0].name, data.owners[0].name)
        assertEquals(obj.owners[1].name, data.owners[1].name)

    }
}