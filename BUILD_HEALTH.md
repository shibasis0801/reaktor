# Build health

What is broken in this build right now, how to see it for yourself, and what fixing it would cost.
Everything here was found by running the test tasks rather than by reading, and every item has the
command that shows it. Nothing here is fixed by a retry.

Checked on 2026-09-28 against `8d328cdb`.

## The whole JS test target is red

```bash
./gradlew jsTest --continue
```

Three tasks fail, and all three trace to Compose reaching a Node test run:

| Task | What happens |
| --- | --- |
| `:compose-flow:jsTest` | 7 of 24 fail. `EdgePathTest` builds a Compose `Path`, which on JS is skia-backed: `ReferenceError: org_jetbrains_skia_Path__1nMake is not defined`. |
| `:reaktor-secrets:jsNodeTest` | The bundle will not load: `ERR_MODULE_NOT_FOUND … skiko.mjs`. |
| `:reaktor-flow:jsNodeTest` | The bundle will not load: `Cannot find package 'compose-flow'`. |

`jsBrowserTest`, where skia would exist, is `SKIPPED` in this build, so these suites have nowhere to
run on JS at all.

**Why a secrets module needs skia.** `reaktor-secrets` holds secret references and an in-memory
store — no UI anywhere in it. It depends on `reaktor-auth`, which applies the Compose plugins and
carries `api(project(":reaktor-ui"))`. So every consumer of auth inherits the toolkit: a bigger web
bundle, a Node test run that cannot start, and a module graph in which "identity" and "widgets" are
the same dependency.

Fixing that is a split — auth logic in one module, its screens in another — and it changes what
every consumer depends on, so it is a decision rather than a patch.

**A red target is worse than a missing one.** Cairn shipped an extractor that threw on every web
launch for months, and the reason nobody saw it is that `jsTest` there had never been run once. A
`jsTest` that is always red is a `jsTest` nobody reads.

## `reaktor-ui` does not compile from clean

```bash
./gradlew :reaktor-ui:compileKotlinJvm --rerun-tasks
```

```
Back-end (JVM) Internal error: Couldn't inline method call:
  'public final fun <get-current> (): T of androidx.compose.runtime.CompositionLocal [inline]'
Caused by: couldn't find inline method
  Landroidx/compose/runtime/CompositionLocal;.getCurrent()Ljava/lang/Object;
```

Deterministic, and invisible in a working tree because the task is `UP-TO-DATE` there — which means
CI, a fresh clone, or anyone who runs `clean` sees it and nobody else does.

The runtime jar is on the classpath and resolves correctly
(`androidx.compose.runtime:runtime-desktop:1.11.0-alpha01`). What it contains is the transformed
signature:

```bash
javap -p androidx/compose/runtime/CompositionLocal.class   # from that jar
#   public final T getCurrent(androidx.compose.runtime.Composer, int);
```

The compiler is looking for `getCurrent()` and the jar holds `getCurrent(Composer, int)`, so the
Compose compiler plugin compiling `reaktor-ui` and the runtime it compiles against disagree about
the ABI of an inline composable property. That is a version pairing, not a code fault:
`compose.version=1.11.0-alpha01` in `gradle.properties` against the Compose compiler bundled with
`kotlin.version=2.3.0`.

Two ways out, both a version decision:

- move Compose to a release known to pair with Kotlin 2.3.0, or
- pin the Compose compiler plugin to the one the 1.11 alpha was built against.

Cairn does not hit this because nothing in it reaches `reaktor-ui`; the modules that do are the ones
downstream of `reaktor-auth`.

## `reaktor-crypto` fails 18 of 20 on the JVM

```bash
./gradlew :reaktor-crypto:jvmTest --rerun-tasks
```

```
java.lang.IncompatibleClassChangeError: Expecting non-static method
  'kotlinx.coroutines.CoroutineDispatcher kotlinx.coroutines.Dispatchers.getDefault()'
  at dev.shibasis.reaktor.crypto.JcaCrypto.randomBytes(JcaCrypto.kt:31)
```

Every test that reaches a dispatcher dies, which here means every test that generates a key or a
random byte — so the module that Cairn's sealing sits on has two passing tests out of twenty. The
failure is in production code (`withContext(Dispatchers.Default)`), not in the tests.

Read literally: the compiled class expects an **instance** `getDefault()`, and the coroutines jar on
the test runtime has a **static** one. On the JVM compile classpath both of these are present:

```
kotlinx-coroutines-core-jvm:1.10.2   (bytecode, @JvmStatic accessors)
kotlinx-coroutines-core:1.10.2       (the multiplatform/metadata variant)
```

A first guess was a version skew — the compile classpath resolved 1.10.1 while
`kotlinx-coroutines-test` pulled 1.10.2 through its BOM at runtime, and the accessor's staticness
differs between them. **Aligning both to 1.10.2 does not fix it**, so that skew was real but not the
cause; the change was reverted rather than left in on a hunch. What is left to explain is why the
metadata variant is on a JVM compile classpath at all, since a JVM compilation resolving
`Dispatchers` from it would see exactly this signature. `commonCoroutines()` in dependeasy adds
`api("org.jetbrains.kotlinx:kotlinx-coroutines-core")` from `commonMain`, which is the normal
spelling, so the next place to look is how the jvm target's compile classpath is assembled — not at
the version numbers.

## `reaktor-service` fails 1 of 5, and `reaktor-secrets` 2 of 3

```
java.lang.NoClassDefFoundError: dev/shibasis/reaktor/service/ServiceInterceptor$DefaultImpls
java.lang.NoClassDefFoundError: dev/shibasis/reaktor/secrets/SecretStore$DefaultImpls
```

`DefaultImpls` is what the compiler emits for interface default methods *unless* it is told to use
real JVM default methods — which `dependeasy`'s `server { }` does, with `-Xjvm-default=all` on the
jvm target. Something on each of these classpaths was compiled on the other side of that flag, so
the caller looks for a class the callee no longer emits. The flag is set per target in
`ServerConfigure.kt`; whether it reaches every compilation of that target, and every module that
publishes an interface consumed across module lines, is the thing to check.

`reaktor-secrets` cannot be re-run from clean to confirm, because from clean it fails earlier, in
`reaktor-ui`, per the item above.

## Two Kotlin versions are declared

```
gradle.properties:      kotlin.version=2.3.0
dependeasy Version.kt:  const val Kotlin = "2.3.0-Beta2"
```

The second pins `kotlin-stdlib` for Android modules (`AndroidDependencies.kt`), so Android builds
compile with 2.3.0 against a 2.3.0-Beta2 stdlib. Tolerated today and not the cause of the item
above, but it is one declaration of the same fact in two places, and the two have already drifted.

## Known flake

`:reaktor-core:jvmTest` → `ConcurrentHashMapBenchmark.correctnessConcurrentPutGet` failed once under
full-suite load with `expected:<100000> but was:<87641>`, and passes in isolation. Either the
`StripedCounter` size is being read before the writers are joined, or the map loses entries during
an incremental resize. Worth separating with a key-by-key sweep rather than a `size()` assertion,
because one of those two answers is a correctness bug in a primitive that `Feature`,
`ObjectDatabase` and several nodes sit on.

## What was actually observed

```bash
./gradlew jvmTest --continue
```

Ran and passed: `reaktor-core`, `reaktor-db`, `reaktor-graph-port`, `reaktor-io`,
`reaktor-performance`. Ran and failed: `reaktor-crypto`, `reaktor-service`. Reported NO-SOURCE:
`reaktor-mcp`, `reaktor-security`, `reaktor-work`.

**That is seven modules out of the fourteen that have a `commonTest` directory.** A root `jvmTest`
did not execute one for `compose-flow`, `reaktor-flow`, `reaktor-auth`, `reaktor-secrets`,
`reaktor-health`, `reaktor-sensors` or `reaktor-flow`, although naming them directly does run them
(`./gradlew :compose-flow:jvmTest` works). So the repo has no single command that runs its own tests,
and this list is what was seen rather than a statement about the whole build. Working that out is
the first thing worth doing here: everything else on this page was found by running one target that
nobody had run.

Cairn is unaffected by all of it. Nothing in that app reaches `reaktor-ui`, `reaktor-auth` or
`reaktor-crypto`, which is also why none of this surfaced while building it.
