@file:JsModule("pdfjs-dist/legacy/build/pdf.worker.mjs")

package dev.shibasis.reaktor.pdf

/** The worker's side of pdf.js, imported as code so it can run on the page's own thread. */
internal external val WorkerMessageHandler: dynamic
