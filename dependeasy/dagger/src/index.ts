import { dag, CacheSharingMode, Container, Directory, Platform, Secret, argument, object, func } from "@dagger.io/dagger"

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
  constructor(private source: Directory, private reaktor: Directory, private toolchainContract: ToolchainContract, private buildName: string,
    private environment: Record<string, string>) {}
  async toolchain(): Promise<Container> {
    const contract = await this.contract()
    const container = dag
      .container({ platform: this.environment.DEPE_CONTAINER_PLATFORM as Platform })
      .from(contract.javaImage)
      .withExec([
        "sh",
        "-c",
        "apt-get update && apt-get install -y --no-install-recommends git curl ca-certificates unzip cmake build-essential pkg-config sqlite3 libgtk-3-dev libwebkit2gtk-4.1-dev libarchive-tools && ln -s /usr/bin/bsdtar /usr/local/bin/tar && " +
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
    const contract = this.toolchainContract
    if (contract.schemaVersion !== 1) throw new Error("Unsupported Dependeasy toolchain schema")
    if (!["linux/amd64", "linux/arm64"].includes(this.environment.DEPE_CONTAINER_PLATFORM))
      throw new Error("Unsupported or missing Dependeasy container platform")
    return contract
  }

  private runtimeEnvironment(container: Container): Container {
    return Object.entries(this.environment).reduce((current, [key, value]) => current.withEnvVariable(key, value), container)
  }

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

  async androidToolchain(): Promise<Container> {
    const sdk = (await this.contract()).androidSdk
    return (await this.toolchain())
      .withExec(["apt-get", "update"])
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
        `$PATH:${sdk.directory}/platform-tools`,
        { expand: true },
      )
  }

  async workspace(): Promise<Container> {
    const contract = await this.contract()
    const prepared = await this.androidToolchain()
    const platform = await prepared.platform()
    return this.useLocalGradleDistribution(
      prepared
        .withDirectory("/work/reaktor", this.reaktor)
        .withDirectory(`/work/${this.buildName}`, this.source)
        .withMountedCache(
          "/work/reaktor/build/dependeasy/tools/hermes",
          dag.cacheVolume(`reaktor-hermes-host-${platform}`),
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
    const browser = dag.container({ platform: this.environment.DEPE_CONTAINER_PLATFORM as Platform }).from(image)
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

type Pipeline = {
  schema: number
  build: string
  engineVersion: string
  browserImage: string
  environment: Record<string, string>
  toolchain: ToolchainContract
  targets: Array<{
    id: { build: string, project: string, name: string }
    effect: string
    host: { platform: string, execution?: string | null }
    invocation: { type: string, tasks: string[] }
    environments: string[]
    runner: string
  }>
}

@object()
export class Dependeasy {
  source: Directory
  reaktor: Directory
  constructor(@argument({ ignore: SOURCE_IGNORES }) source: Directory, @argument({ ignore: SOURCE_IGNORES }) reaktor: Directory) {
    this.source = source
    this.reaktor = reaktor
  }
  private async pipeline(): Promise<Pipeline> {
    const value: Pipeline = JSON.parse(await this.source.file("dagger/pipeline.json").contents())
    if (value.schema !== 1 || !/^[a-zA-Z0-9_-]+$/.test(value.build)) throw new Error("Unsupported Dependeasy pipeline")
    return value
  }
  @func()
  async list(): Promise<string[]> {
    return (await this.pipeline()).targets.map(node => node.id.build + node.id.project.replace(/:$/, "") + ":" + node.id.name)
  }
  @func()
  async run(target: string, targetEnv: string = "prod", cloudflareApiToken?: Secret): Promise<string> {
    const pipeline = await this.pipeline()
    const node = pipeline.targets.find(item => item.id.build + item.id.project.replace(/:$/, "") + ":" + item.id.name === target ||
      item.id.project === ":" && item.id.name === target)
    if (!node) throw new Error(`Unknown or unavailable container target '${target}'; use list`)
    if (node.host.platform === "Macos" || node.host.execution === "Local" || !node.invocation.tasks?.length)
      throw new Error("This target requires a physical host")
    const { DEPE_CONTAINER_PLATFORM_OVERRIDES, ...environment } = pipeline.environment
    const platforms: Record<string, string> = JSON.parse(DEPE_CONTAINER_PLATFORM_OVERRIDES ?? "{}")
    const id = node.id.build + node.id.project.replace(/:$/, "") + ":" + node.id.name
    environment.DEPE_CONTAINER_PLATFORM = platforms[id] ?? environment.DEPE_CONTAINER_PLATFORM
    const runtime = new BuildRuntime(this.source, this.reaktor, pipeline.toolchain, pipeline.build, environment)
    let container = await (node.runner === "browser" ? runtime.browserWorkspace(pipeline.browserImage) : runtime.workspace())
    if (node.effect === "Deploy") {
      if (!cloudflareApiToken) throw new Error("Deploy requires cloudflare-api-token")
      container = container.withSecretVariable("CLOUDFLARE_API_TOKEN", cloudflareApiToken)
        .withEnvVariable("ORG_GRADLE_PROJECT_dependeasy.environment", checkedEnvironment(targetEnv, node.environments))
    }
    return container.withExec(["./gradlew", ...node.invocation.tasks, "--console=plain"]).stdout()
  }
}
