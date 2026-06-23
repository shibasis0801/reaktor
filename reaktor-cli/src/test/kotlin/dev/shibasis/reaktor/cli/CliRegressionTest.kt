package dev.shibasis.reaktor.cli

import com.github.ajalt.mordant.input.KeyboardEvent
import kotlin.io.path.createTempDirectory

fun main() {
    deployPickerListsDeployTargetsAndScriptAliases()
    pickerFiltersFuzzilyAndEnterSelectsHighlightedMatch()
    pickerMovesWithArrowAndCtrlKeys()
    pickerEditsAndCancels()
}

private fun deployPickerListsDeployTargetsAndScriptAliases() {
    val root = createTempDirectory("reaktor-cli-test").toFile()
    try {
        root.resolve("targets/appWeb").mkdirs()
        val project = ReaktorProject(
            root = root,
            name = "sample",
            targets = mapOf(
                "web" to Target(
                    name = "web",
                    runtime = "web",
                    workspace = "targets/appWeb",
                    deploy = "deployWeb",
                ),
            ),
            scripts = mapOf(
                "deployWeb" to "wrangler deploy",
                "deployConfig" to "wrangler deploy",
                "deployChat" to "wrangler deploy",
            ),
            workspaces = listOf("targets/appWeb"),
            gradleModules = emptyList(),
        )

        val options = project.deployPickerOptions(emptyList())
        val labels = options.map { it.label }

        expect("appWeb" in labels, "declared target workspace should be selectable as appWeb")
        expect("config" in labels, "deployConfig should expose the config script alias")
        expect("chat" in labels, "deployChat should expose the chat script alias")
        expect("web" !in labels, "duplicate deployWeb alias should be hidden behind appWeb")

        val config = options.first { it.label == "config" }
        expect(config.detail.contains("npm run deployConfig"), "config option should show its backing deploy script")
    } finally {
        root.deleteRecursively()
    }
}

private fun pickerFiltersFuzzilyAndEnterSelectsHighlightedMatch() {
    val state = PickerState(deployOptions())

    "config".forEach { char ->
        expect(state.handleKey(key(char.toString())) == PickerResult.Continue, "typing should filter without selecting")
    }
    expect(state.currentQuery == "config", "query should update as keys are typed")
    expect(
        state.visibleOptions().map { it.value } == listOf("configServer"),
        "config should match only configServer",
    )
    expect(
        state.handleKey(key("Enter")) == PickerResult.Done("configServer"),
        "enter should select the highlighted match",
    )
}

private fun pickerMovesWithArrowAndCtrlKeys() {
    val arrowState = PickerState(deployOptions())
    expect(arrowState.currentCursorIndex == 0, "picker should start at the first row")
    arrowState.handleKey(key("ArrowDown"))
    expect(arrowState.currentCursorIndex == 1, "arrow down should move to the second row")
    expect(arrowState.handleKey(key("Enter")) == PickerResult.Done("chatServer"), "enter should select cursor row")

    val ctrlState = PickerState(deployOptions())
    ctrlState.handleKey(key("j", ctrl = true))
    ctrlState.handleKey(key("k", ctrl = true))
    expect(ctrlState.currentCursorIndex == 0, "ctrl-j/ctrl-k should move down/up")
}

private fun pickerEditsAndCancels() {
    val editState = PickerState(deployOptions())
    editState.handleKey(key("m"))
    editState.handleKey(key("e"))
    editState.handleKey(key("Backspace"))
    expect(editState.currentQuery == "m", "backspace should edit the live query")
    editState.handleKey(key("u", ctrl = true))
    expect(editState.currentQuery == "", "ctrl-u should clear the live query")

    val cancelState = PickerState(deployOptions())
    expect(cancelState.handleKey(key("Escape")) == PickerResult.Cancel, "escape should cancel selection")
}

private fun deployOptions(): List<PickerOption> =
    listOf(
        PickerOption("configServer", detail = "service - npm run deployConfig", keywords = listOf("config")),
        PickerOption("chatServer", detail = "service - npm run deployChat", keywords = listOf("chat")),
        PickerOption("messagingServer", detail = "service - npm run deployMessaging", keywords = listOf("messages")),
    )

private fun expect(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
}

private fun key(value: String, ctrl: Boolean = false): KeyboardEvent =
    KeyboardEvent(value, ctrl = ctrl)
