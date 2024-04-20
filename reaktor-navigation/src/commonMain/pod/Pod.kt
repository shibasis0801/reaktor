package app.mehmaan.navigation.pod

import app.mehmaan.navigation.route.Junction

/*
Imagine Pods as Lego Blocks that you can join together to build powerful flows
A Pod has a Junction, dependencies to plug and events to emit

Event Driven Pod Orchestration decouples each feature from the other
A pod has dependencies that you pass
And an EventNotifier function to send back events
A pod also has the junction that you attach to your app

A junction is an independent block of screens and functionality
This eventNotifier needs to be exposed to all routes underneath

todo -> Should it be possible to have hierarchical pods ?
Maybe an enhancement
*/
abstract class Pod<T: PodEvents>(
    val name: String,
    val eventNotifier: (T) -> Unit = {}
) {
    public val junction: Junction = Junction(name) {}
}

/*
For an event driven app, all event groups derive from this interface.
 */
interface PodEvents {}
