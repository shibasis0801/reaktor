@file:JsModule("pdfjs-dist/legacy/build/pdf.mjs")

package dev.shibasis.reaktor.pdf

/** pdf.js's entry point. Returns a loading task whose `promise` resolves to the document. */
internal external fun getDocument(source: dynamic): dynamic
