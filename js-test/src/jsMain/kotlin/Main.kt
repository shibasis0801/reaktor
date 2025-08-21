import dev.shibasis.reaktor.core.annotations.Expose
import kotlinx.coroutines.delay

@kotlinx.serialization.Serializable
data class User(val name: String)

@Expose
class MyApi {
    suspend fun getUser(): User {
        delay(1000)
        return User("John Doe")
    }
}
