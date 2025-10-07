package dev.shibasis.reaktor.navigation.containers

//
//class NestedContainer<Metadata: MultiStackItemMetadata>(
//    val start: String,
//    error: Screen<Input> = ErrorScreen(),
//    private val builder: MultiStackContainer<Metadata>.() -> Unit = {}
//): Container(Switch()) {
//    private val containers = linkedMapOf<String, Container>()
//    private val activeContainer = Observable<Container?>(null)
//
//
//    override fun consumesBackEvent(): Boolean {
//        return activeContainer.value?.consumesBackEvent() ?: false
//    }
//
//    @Composable
//    override fun Render(props: ContainerInputs) {
//        activeContainer.value?.Render(ContainerInputs())
//    }
//
//    override fun push(screenPair: ScreenPair) {
//        activeContainer.value?.push(screenPair)
//    }
//
//    override fun replace(screenPair: ScreenPair) {
//        activeContainer.value?.replace(screenPair)
//    }
//
//    override fun pop() {
//        activeContainer.value?.pop()
//    }
//}
