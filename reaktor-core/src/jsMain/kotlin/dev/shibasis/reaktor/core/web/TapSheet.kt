package dev.shibasis.reaktor.core.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

class TapSheet private constructor(
    private val backdrop: HTMLElement,
    val content: HTMLElement,
    private val palette: TapSheetPalette,
    private val returnFocus: HTMLElement?,
    private val onDismiss: () -> Unit,
) {
    var isOpen = true
        private set

    private val escape: (Event) -> Unit = { event ->
        if ((event as? KeyboardEvent)?.key == "Escape") {
            event.stopPropagation()
            event.preventDefault()
            dismiss()
        }
    }

    fun button(label: String, primary: Boolean = true, onTap: () -> Unit): HTMLButtonElement {
        val button = document.createElement("button") as HTMLButtonElement
        button.type = "button"
        button.textContent = label
        button.style.cssText = if (primary) palette.primaryButton else palette.secondaryButton
        button.addEventListener("click", { onTap() })
        content.appendChild(button)
        if (primary && content.querySelector("button") === button) button.focus()
        return button
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        document.removeEventListener("keydown", escape, true)
        backdrop.remove()
        returnFocus?.focus()
    }

    fun dismiss() {
        if (!isOpen) return
        close()
        onDismiss()
    }

    companion object {
        fun open(title: String, message: String? = null, onDismiss: () -> Unit): TapSheet {
            val palette = TapSheetPalette.current()
            val backdrop = element("div", palette.backdrop)
            backdrop.setAttribute("data-reaktor-tap-sheet", "")
            val returnFocus = document.activeElement as? HTMLElement
            val card = element("div", palette.card)
            card.tabIndex = -1
            card.setAttribute("role", "dialog")
            card.setAttribute("aria-modal", "true")
            card.setAttribute("aria-label", title)
            val heading = element("p", palette.title)
            heading.textContent = title
            card.appendChild(heading)
            if (message != null) {
                val body = element("p", palette.message)
                body.textContent = message
                card.appendChild(body)
            }
            val content = element("div", palette.actions)
            card.appendChild(content)
            backdrop.appendChild(card)

            val sheet = TapSheet(backdrop, content, palette, returnFocus, onDismiss)
            backdrop.addEventListener("click", { event -> if (event.target === backdrop) sheet.dismiss() })
            document.addEventListener("keydown", sheet.escape, true)
            (document.body ?: document.documentElement)?.appendChild(backdrop)
            card.focus()
            return sheet
        }

        private fun element(tag: String, css: String): HTMLElement =
            (document.createElement(tag) as HTMLElement).apply { style.cssText = css }
    }
}

internal class TapSheetPalette(
    val backdrop: String,
    val card: String,
    val title: String,
    val message: String,
    val actions: String,
    val primaryButton: String,
    val secondaryButton: String,
) {
    companion object {
        fun current(): TapSheetPalette {
            val dark = window.matchMedia("(prefers-color-scheme: dark)").matches
            val surface = if (dark) "#1c1b18" else "#faf8f3"
            val ink = if (dark) "#f2eee5" else "#1b1820"
            val quiet = if (dark) "#b8b2a6" else "#5a5560"
            val primaryFill = if (dark) "#f2eee5" else "#1b1820"
            val primaryInk = if (dark) "#1b1820" else "#ffffff"
            val font = "font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;"
            return TapSheetPalette(
                backdrop = "position:fixed;inset:0;z-index:2147483646;display:flex;align-items:flex-end;" +
                    "justify-content:center;background:rgba(12,11,9,.48);$font",
                card = "box-sizing:border-box;outline:none;width:calc(100% - 24px);max-width:420px;margin:0 12px 12px;" +
                    "padding:22px 20px calc(18px + env(safe-area-inset-bottom));border-radius:22px;" +
                    "background:$surface;color:$ink;box-shadow:0 18px 48px rgba(0,0,0,.28);",
                title = "margin:0 0 6px;font-size:17px;font-weight:650;line-height:1.3;",
                message = "margin:0 0 14px;font-size:15px;line-height:1.45;color:$quiet;word-break:break-word;",
                actions = "display:flex;flex-direction:column;gap:10px;margin-top:10px;align-items:stretch;",
                primaryButton = "appearance:none;border:0;border-radius:999px;min-height:48px;padding:12px 18px;" +
                    "font:600 16px/1.2 system-ui,-apple-system,sans-serif;background:$primaryFill;color:$primaryInk;cursor:pointer;",
                secondaryButton = "appearance:none;border:0;border-radius:999px;min-height:44px;padding:10px 18px;" +
                    "font:500 15px/1.2 system-ui,-apple-system,sans-serif;background:transparent;color:$quiet;cursor:pointer;",
            )
        }
    }
}
