# reaktor-media

`reaktor-media` is the media capability layer for Reaktor apps.

## Responsibilities

- camera adapter surface
- gallery/media selection hooks
- image loading and caching helpers
- speech recognition and synthesis abstractions
- shared media-related support utilities

## Current areas

- Android camera integration
- Darwin camera integration
- Darwin gallery support
- shared caches and image utilities
- speech recognizer / synthesizer abstractions

## What this module is not

It is not a full backend media pipeline. Upload authorization, storage, and background work are usually composed with:
- `reaktor-auth`
- `reaktor-db`
- `reaktor-work`
- product-specific media services
