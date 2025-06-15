# Reaktor (WIP, ETA July 2025)

Libraries for scalable cross-platform development based on Kotlin Multiplatform.
Useful abstractions and utilities to build tech better.
Reaktor is the root library, and FlatInvoker focuses on using FlexBuffers to define foreign function interfaces.

# for i in `echo location work notification`;do for j in `ls reaktor-$i/src`; do mkdir reaktor-$i/src/$j/kotlin/dev/shibasis/reaktor/$i; done; done

## FlatInvoker

FlatInvoker focuses on using FlexBuffers to perform foreign function invokations(ffi)

Application Areas:

1. React Native: Native modules for React Native on mobile
2. Hermes FFI: TypeScript modules for Native Code on mobile

## Dependeasy

`Dependeasy` is a plugin designed to assist with multi-platform dependencies. It offers abstractions for setting up multiplatform projects quickly, provides CMake support for Kotlin/Native, and includes size benchmarking tools.

## Benchmarking

Use [react-native-performance](https://github.com/Shopify/react-native-performance) and kotlinx-benchmark for benchmarking.

Roadmap [documentation](https://docs.google.com/document/d/1dwy5Cy9FO5CpWikQ4a2AUtIu2tHRKMmm9ezaycKIp9A/edit).

## Architecture

The architecture is meant to be powerful but as simple as it can be but no simpler.
The primary idea is Separation of Concerns as long as it does not cause too much overhead.

### General Units

#### Component

A component is the most basic unit of execution. It is lifecycle aware and gets events if the parent container's lifecycle changes (app goes to background, etc).

#### Adapter

An adapter tries to abstract away the platform specific implementation of a component and provide a common interface.

It holds a weak reference to the controller, and lets you get best of both worlds. We like to believe we can separate all our logic from platform specific behaviour but we can't. Most libraries for each platform need some platform dependencies. Storing these dependencies can cause memory leak issues and other problems.

Adapters solve this by providing a common interface but letting you access the platform specific implementation safely when you need to.

##### Adapter Capabilities

When we write an abstraction over platforms, it is natural that the common abstraction would be the least common denominator with only common behaviour. But sometimes we need to access platform specific behaviour.

Capabilities are sub-interfaces that can be implemented by individual platforms. This lets you write your UI logic in a platform agnostic way, but still access platform specific behaviour when you need to.

They feel like globals, and can have sub-interfaces. For example Camera support on Android and iOS can be different, so CameraAdapter would be

```kotlin
abstract class CameraAdapter<Controller>(
    controller: Controller
): Adapter<Controller>(controller) {
    enum class CameraStart {
        Success,
        ControllerFailure,
        PermissionFailure,
        CameraFailure,
    }

    abstract suspend fun start(): CameraStart
    @Composable
    abstract fun Render()

    // Optional Capability
    interface FileCapability {
        fun storeFile(name: String)
    }

    // Optional Capability
    interface AnalyserCapability {
        fun addAnalyser(): Boolean
    }
    // ...
}


class AndroidCameraAdapter(
    activity: ComponentActivity,
    val permissionAdapter: PermissionAdapter<*>
): CameraAdapter<ComponentActivity>(activity), CameraAdapter.FileCapability, CameraAdapter.AnalyserCapability

// but, for web you may not have file capability.

class WebCameraAdapter(
    webComponent: WebComponent,
    val permissionAdapter: PermissionAdapter<*>
): CameraAdapter<WebComponent>(webComponent), CameraAdapter.AnalyserCapability


// You can easily check for capabilities using Kotlin's type system

fun useCamera(cameraAdapter: CameraAdapter<*>) {
    if(cameraAdapter is CameraAdapter.AnalyserCapability) {
        cameraAdapter.addAnalyser()
    }
    if(cameraAdapter is CameraAdapter.FileCapability) {
        cameraAdapter.storeFile("file")
    }
    // ...
}


// See how the above lets you write UI code without bothering with platform checks.


```

#### Feature

A Feature accessed as Feature.Camera or something is an abstraction over platform functionality. You initialize them during App startup and they exist outside of DependencyInjection context.

It should generally be an Adapter, because ephemeral dependencies should be injected via Koin DI.

```kotlin
// Super simple declaration which feels like a Global.
var Feature.Camera by CreateSlot<CameraAdapter<*>>()
```

### Data Units

Result\<T>s are used heavily in the architecture. They are the standard way to denote Success and Failure. try-catch prevents optimizations by compilers and also disrupts the flow of the code.

All of the following units are available from dependency injection (koin), so can be scoped as needed.

#### Api

Call your remote APIs / DBs / anything. You need to return a Result\<T> indicating success or failure. Helper functions succeed / fail are provided.

```kotlin
// Adapter here, ensures swapping supabase is easy.
// Ideally it should also abstract away library details, but need to be pragmatic and not idealistic.
open class FriendApi: SupabaseAdapter() {
    suspend fun getFriendChats(userId: Int) = invokeSuspendResult {
        val columns = Columns.raw("""
    friend_id,
    chat_id,
    users!friends_friend_id_fkey1(email)
    """.trimIndent())
        succeed(
            from("friends")
            .select(columns = columns)
            {
                filter {
                    eq("user_id", userId)
                }
            }
            .decodeAs<List<FriendFetch>>()
        )
    }
}
```

#### Repository

A repository takes an Api and a ObjectDatabase. You create an ObjectStore from this database and use it to store and retrieve objects.

```kotlin
class FriendRepository(
    database: ObjectDatabase,
    val friendApi: FriendApi,
) {
    private val friendStore = ObjectStore(database, "friend_store")
    suspend fun getFriendChats(userId: Int): Result<List<FriendFetch>> {
        val (data, _) = friendStore.writeThrough("$userId:friend_chats") {
            friendApi.getFriendChats(userId)
        }
        return data
    }
}

// Look through the ObjectStore / ObjectDatabase code to understand how they function.
```

#### Interactor

An interactor is the bridge between UI and Data. It has repositories as dependencies and carries out business logic. It should hold transformations and can hold state, but try to keep them stateless.

It is also responsible to provide data in a way easy for UI to use (and so that UI can avoid LaunchedEffect, etc which should be used sparingly)

```kotlin

class ChatInteractor(
    val friendRepository: FriendRepository,
    val groupChatRepository: GroupChatRepository,
    val messageRepository: MessageRepository,
    val userRepository: UserRepository
) {
    // combines both
    suspend fun fetchChatList(userId: Int): List<ChatListItem>

    // uses userRepository, friendRepository & messageRepository
    private suspend fun chats(userId: Int): List<ChatListItem>

    // uses userRepository, groupChatRepository & messageRepository
    private suspend fun groupChats(userId: Int): List<ChatListItem>

}
```

#### ViewModels

Ideally your UI should directly deal with stateless interactors, but if for some reason your UI needs state and also has complex interactions which don't make sense to put inside one interactor, or requires orchestrating multiple interactors.

You use a ViewModel. A ViewModel contains multiple interactors and orchestrates them.
Avoid them as long as you can, as they have the risk of becoming god-classes and also letting you build monolithic god-ui functions. Both are generally bad unless really needed.

### Routing Units

For routing, we have Screens, Switches and Containers. Each contains the former.

#### Route

This is the base class. It holds a sub-interface(similar to Adapter.Capability, but these are not Adapters).

```kotlin
sealed class Route(var pattern: RoutePattern = RoutePattern()) {
    var parent: Route? = null
    var container: Container? = null

    interface Render<out T: Props> {
        @Composable
        fun Render(props: @UnsafeVariance T)
    }
}
```

#### Screen

A screen contains a Render function, and hosts a UI. It is a route which doesn't have its own navigation.

```kotlin
abstract class Screen<out T: Props>(val defaultProps: T): Route(), Route.Render<T> {
    fun with(props: @UnsafeVariance T, vararg params: Pair<String, Any>) =
        ScreenPair(this, props.also {
            params.forEach { pair ->
                it.params[pair.first] = pair.second.toString()
            }
        })

    fun screenPair() = with(defaultProps)
}
```

#### Switch

A switch contains a bunch of screens and nested switches, but still does not have its own navigation.

```kotlin
class Switch(
    val home: Screen<Props> = ErrorScreen("Home Screen not selected"),
    val error: Screen<Props> = ErrorScreen(),
    private val builder: Switch.() -> Unit = {}
): Route()
```

#### Container

The meat of the framework, a container holds a Switch and multiple back stacks. This handles navigation inside of it. It also holds a Container UI which hosts the active Screen.

```kotlin
abstract class Container(
    val switch: Switch
): Route(), Route.Render<Props> {
    constructor(
        home: Screen<Props> = ErrorScreen("Home Screen not selected"),
        error: Screen<Props> = ErrorScreen(),
        builder: Switch.() -> Unit = {}
    ): this(Switch(home, error, builder))

    abstract fun consumesBackEvent(): Boolean
    abstract fun push(screenPair: ScreenPair)
    abstract fun replace(screenPair: ScreenPair)
    abstract fun pop()
}
```

You can extend this to create BottomNavigation, TabbedNavigation, etc

```kotlin
open class SingleStackContainer(
    switch: Switch
): Container(switch) {
    constructor(
        home: Screen<Props> = ErrorScreen("Home Screen not selected"),
        error: Screen<Props> = ErrorScreen(),
        builder: Switch.() -> Unit = {}
    ): this(Switch(home, error, builder))

    private val screenStack = ObservableStack<ScreenPair>()

    override fun build() {
        super.build()
        push(switch.home.screenPair())
    }

    @Composable
    override fun Render(props: Props) {
        val current by screenStack.top.collectAsState()
        current?.let {
            it.screen.Render(it.props)
        }
    }

    override fun consumesBackEvent(): Boolean {
        return screenStack.size > 1
    }

    override fun push(screenPair: ScreenPair) {
        screenStack.push(screenPair)
    }

    override fun replace(screenPair: ScreenPair) {
        screenStack.replace(screenPair)
    }

    override fun pop() {
        screenStack.pop()
    }
}
```

[MultiStackContainer](https://github.com/shibasis0801/flatInvoker/blob/main/reaktor-navigation/src/commonMain/kotlin/dev/shibasis/reaktor/navigation/containers/MultiStackContainer.kt) is also provided, but is a WIP and you would generally use a concrete class like BottomNavigationContainer, TabbedNavigationContainer, etc.

```kotlin
class BottomBarContainer(
    start: String,
    error: Screen<Props> = ErrorScreen(),
    builder: MultiStackContainer<BottomBarItem>.() -> Unit = {}
) : MultiStackContainer<BottomBarItem>(start, error, builder) {
    @Composable
    override fun Render(props: Props) {
        val currentKey by currentKey.collectAsState()
        Scaffold(
            bottomBar = {
                NavigationBar {
                    // We create an item for each key
                    metadata.keys.forEach { key ->
                        NavigationBarItem(
                            selected = currentKey == key,
                            onClick = { switchStack(key) },
                            icon = { Icon(metadata[key]!!.icon, key) },
                            label = { Text(key) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(props.modifier, contentAlignment = Alignment.Center) {
                Content()
            }
        }
    }
}
```

### Sample Usage

```kotlin
SingleStackContainer(startScreen) {
            switch("profile", profileScreen) {
                screen("{id}", friendProfileScreen)
                screen("edit", editProfileScreen)
            }

            container(
                "home",
                BottomBarContainer("Chats") {
                        item(
                            BottomBarItem("Chats", Icons.AutoMirrored.Filled.Chat),
                            switch("chats", chatsScreen) {
                                screen("{id}", chatScreen)
                            })

                        item(
                            BottomBarItem("Campaigns", Icons.Filled.Campaign),
                            screen("campaigns", campaignScreen)
                        )

                        item(
                            BottomBarItem("Directs", Icons.Filled.People),
                            container("directs", TabbedContainer("Groups") {
                                item(
                                    TabBarItem("Groups"),
                                    switch("groups", groupsScreen) {
                                        switch("{id}", groupChatScreen) {
                                            screen("members", groupMembersScreen)
                                        }
                                    }
                                )

                                item(
                                    TabBarItem("Friends"),
                                    switch("friends", friendsScreen) {
                                        screen("{id}", friendProfileScreen)
                                    }
                                )
                            })
                        )
                    }
            )
        }
```
