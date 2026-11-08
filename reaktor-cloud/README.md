# reaktor-cloud

Dependeasy configures this module as a Kotlin Multiplatform library with JVM,
Android and Apple targets. Cloud inventory uses the shared Reaktor tooling providers.
Build and delivery operations come from canonical Dependeasy model nodes.

## Build and check

From the Reaktor repository root:

```sh
./gradlew :reaktor-cloud:compileKotlinJvm :reaktor-cloud:jvmTest --console=plain
```

## Contents

- `WranglerInventoryProvider` reads the Cloudflare inventory from a repository's
  `wrangler.json` files, without credentials or live API calls.
- `verifyWrangler` prints that inventory for a repository:
  `./gradlew :reaktor-cloud:verifyWrangler -PwranglerPath=/path/to/repo`.
- `place.CloudLocator` finds where the running process is, from Google Cloud
  metadata or an egress lookup, and where an endpoint it reaches is hosted.
- `place.AirportPlaces` maps airport codes to places.
