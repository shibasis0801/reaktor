import { dag, CacheSharingMode, Container, Directory, File, Platform } from "@dagger.io/dagger"

export const SOURCE_IGNORES = [
  "**/node_modules", "**/build", "**/build-*", "**/dist", "**/.gradle", "**/.kotlin",
  "**/.wrangler", ".github_modules", "agent", "tmp", "logs",
]
type ToolchainContract = {
  schemaVersion: number
  javaImage: string
  gradleUrl: string
  gradleSha256: string
  gradleArchive: string
  androidSdk: {
    directory: string
    commandLineTools: string
    downloadUrl: string
    sha256: string
    packages: string[]
  }
}

export function checkedEnvironment(value: string, allowed: readonly string[]): string {
  if (!allowed.includes(value)) throw new Error(`Unsupported environment '${value}'; choose ${allowed.join(', ')}`)
  return value
}

export class BuildRuntime {
  constructor(private source: Directory, private reaktor: Directory, private contractFile: File, private buildName: string,
    private environment: Record<string, string>) {}
  /** Build JDK and verified Gradle distribution; Dependeasy installs JavaScript tools. */
  async toolchain(): Promise<Container> {
    const contract = await this.contract()
    const container = dag
      // The pinned Android command-line tools and NDK contain Linux x86-64 executables.
      .container({ platform: "linux/amd64" as Platform })
      .from(contract.javaImage)
      .withExec([
        "sh",
        "-c",
        "apt-get update && apt-get install -y --no-install-recommends git curl ca-certificates unzip cmake build-essential pkg-config libgtk-3-dev libwebkit2gtk-4.1-dev && " +
          "rm -rf /var/lib/apt/lists/*",
      ])
      .withExec([
        "sh", "-ec",
        'mkdir -p "$(dirname "$3")"; curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 --connect-timeout 30 -o "$3" "$1"; printf "%s  %s\\n" "$2" "$3" | sha256sum --check --status',
        "gradle-distribution", contract.gradleUrl, contract.gradleSha256, contract.gradleArchive,
      ])
      .withMountedCache("/root/.gradle", dag.cacheVolume(`${this.buildName}-gradle`), { sharing: CacheSharingMode.Locked })
      .withMountedCache("/root/.konan", dag.cacheVolume(`${this.buildName}-konan`), { sharing: CacheSharingMode.Locked })
      .withMountedCache("/root/.local/share/pnpm/store", dag.cacheVolume("dependeasy-pnpm"), { sharing: CacheSharingMode.Locked })
    return this.runtimeEnvironment(container)
  }

  private async contract(): Promise<ToolchainContract> {
    const contract: ToolchainContract = JSON.parse(await this.contractFile.contents())
    if (contract.schemaVersion !== 1) throw new Error("Unsupported Dependeasy toolchain schema")
    return contract
  }

  private runtimeEnvironment(container: Container): Container {
    // CI shares an 8 GiB Docker VM; one compiler heap keeps concurrent tasks bounded.
    const configured = container
      .withEnvVariable("GRADLE_OPTS", "-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs='-Xmx2g -XX:MaxMetaspaceSize=768m -XX:ReservedCodeCacheSize=128m' -Dorg.gradle.workers.max=1 -Dorg.gradle.vfs.watch=false -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process")
      .withEnvVariable("NODE_OPTIONS", "--max-old-space-size=384")
    return Object.entries(this.environment).reduce((current, [key, value]) => current.withEnvVariable(key, value), configured)
  }

  /**
   * Points both repos' wrappers at the pre-seeded distribution.
   *
   * Rewrites the mounted copies only — the checked-in files are untouched, so `./gradlew` stays
   * the canonical entrypoint everywhere and a developer's local wrapper still resolves normally.
   */
  private useLocalGradleDistribution(ctr: Container, archive: string): Container {
    return ctr.withExec([
      "sh",
      "-c",
      `for p in /work/${this.buildName}/gradle/wrapper/gradle-wrapper.properties ` +
        `/work/reaktor/gradle/wrapper/gradle-wrapper.properties; do ` +
        `[ -f "$p" ] || continue; ` +
        `sed -i "s|^distributionUrl=.*|distributionUrl=file\\\\:${archive}|" "$p"; ` +
        `sed -i "s|^validateDistributionUrl=.*|validateDistributionUrl=false|" "$p"; ` +
        `done`,
    ])
  }

  /**
   * toolchain + Android SDK/NDK/CMake. The KMP composite build needs the Android SDK
   * present just to CONFIGURE the Android library variants, even for JVM/JS-only tasks.
   */
  async androidToolchain(): Promise<Container> {
    const sdk = (await this.contract()).androidSdk
    return (await this.toolchain())
      .withExec(["apt-get", "update"])
      // Native compilation is task-scoped; configuration no longer builds Hermes.
      .withExec([
        "sh", "-ec",
        "apt-get install -y --no-install-recommends ninja-build && " +
          "rm -rf /var/lib/apt/lists/*",
      ])
      .withEnvVariable("ANDROID_SDK_ROOT", sdk.directory)
      .withEnvVariable("ANDROID_HOME", sdk.directory)
      .withMountedCache(`${sdk.directory}`, dag.cacheVolume(`${this.buildName}-android-sdk`))
      .withFile("/opt/dependeasy/android/install-sdk.sh", this.reaktor.file("dependeasy/android/install-sdk.sh"))
      .withExec([
        "sh", "/opt/dependeasy/android/install-sdk.sh", sdk.directory, sdk.commandLineTools, sdk.downloadUrl, sdk.sha256, ...sdk.packages,
      ])
      .withEnvVariable(
        "PATH",
        `${sdk.directory}/platform-tools:$PATH`,
        { expand: true },
      )
  }

  /** Android toolchain + both repos as siblings; workdir = bestbuds. The single source of the layout. */
  async workspace(): Promise<Container> {
    const contract = await this.contract()
    return this.useLocalGradleDistribution(
      (await this.androidToolchain())
        .withDirectory("/work/reaktor", this.reaktor)
        .withDirectory(`/work/${this.buildName}`, this.source)
        .withMountedCache(
          "/work/reaktor/build/dependeasy/tools/hermes",
          dag.cacheVolume("reaktor-hermes-host-linux-amd64"),
        ),
      contract.gradleArchive,
    ).withWorkdir(`/work/${this.buildName}`)
  }

  async commandWorkspace(): Promise<Container> {
    return (await this.workspace()).withExec(["./gradlew", "javascriptToolchain", "pnpmInstall", "--console=plain"])
      .withEnvVariable("PATH", `/work/${this.buildName}/build/dependeasy/tools/javascript/bin:$PATH`, { expand: true })
  }

  async browserWorkspace(image: string): Promise<Container> {
    const sdk = (await this.contract()).androidSdk
    const prepared = (await this.workspace()).withExec([
      "./gradlew", "javascriptToolchain", "pnpmInstall", "--console=plain",
    ])
    const browser = dag.container({ platform: "linux/amd64" as Platform }).from(image)
      .withDirectory("/work", prepared.directory("/work"))
      .withDirectory("/opt/java/openjdk", prepared.directory("/opt/java/openjdk"))
      .withDirectory("/opt/gradle-dist", prepared.directory("/opt/gradle-dist"))
      .withMountedCache("/root/.gradle", dag.cacheVolume(`${this.buildName}-gradle`), { sharing: CacheSharingMode.Locked })
      .withMountedCache("/root/.konan", dag.cacheVolume(`${this.buildName}-konan`), { sharing: CacheSharingMode.Locked })
      .withMountedCache(sdk.directory, dag.cacheVolume(`${this.buildName}-android-sdk`), { sharing: CacheSharingMode.Locked })
      .withEnvVariable("JAVA_HOME", "/opt/java/openjdk")
      .withEnvVariable("ANDROID_HOME", sdk.directory)
      .withEnvVariable("ANDROID_SDK_ROOT", sdk.directory)
      .withEnvVariable("PATH", `/work/${this.buildName}/build/dependeasy/tools/javascript/bin:/opt/java/openjdk/bin:$PATH`, { expand: true })
      .withWorkdir(`/work/${this.buildName}`)
    return this.runtimeEnvironment(browser)
  }
}
