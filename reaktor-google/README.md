# reaktor-google

`reaktor-google` currently focuses on Google Cloud Pub/Sub integration.

## What it provides

- shared Pub/Sub data types
- adapter abstraction for publish / subscribe / pull / ack flows
- platform-specific adapters for JVM, Android, Darwin, and Web
- a slot in `Feature.GooglePubSub` for runtime installation

## Important types

- `PubSubTopic`
- `PubSubSubscription`
- `PubSubMessage`
- `PulledPubSubMessage`
- `PubSubAdapter`

## Intended use

Use this module when a product needs:
- Pub/Sub publishing from shared code
- a single abstraction across server and client runtimes
- service endpoints backed by Pub/Sub topics

## Status

Pub/Sub is the primary implemented surface here. Other Google integrations should only live here if they can be kept product-neutral.
