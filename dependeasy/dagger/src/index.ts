import { dag, CacheSharingMode, Container, Directory, Platform, ReturnType, Secret, argument, object, func } from "@dagger.io/dagger"

export const SOURCE_IGNORES = [
  "**/node_modules", "**/build", "**/build-*", "**/dist", "**/.gradle", "**/.kotlin",
  "**/.wrangler", ".github_modules", "agent", "tmp", "logs",
]
type ToolchainContract = {
  schemaVersion: number
  javaImage: string
  browserImage: string
  gradleUrl: string
  gradleSha256: string
  gradleArchive: string
  packages: string[]
  caches: Record<string, string>
  files: Record<string, string>
  androidSdk: {
    directory: string
    commandLineTools: string
    downloadUrl: string
    sha256: string
    packages: string[]
  }
}

export class BuildRuntime {
  constructor(private source: Directory, private reaktor: Directory, private toolchainContract: ToolchainContract, private buildName: string,
    private environment: Record<string, string>) {}
  async toolchain(): Promise<Container> {
    const contract = await this.contract()
    const container = dag
      .container({ platform: this.environment.DEPE_CONTAINER_PLATFORM as Platform })
      .from(contract.javaImage)
      .withExec(["apt-get", "update"])
      .withExec(["apt-get", "install", "-y", "--no-install-recommends", ...contract.packages])
      .withExec(["ln", "-s", "/usr/bin/bsdtar", "/usr/local/bin/tar"])
      .withExec(["rm", "-rf", "/var/lib/apt/lists"])
      .withExec(["mkdir", "-p", contract.gradleArchive.substring(0, contract.gradleArchive.lastIndexOf("/"))])
      .withExec(["curl", "-fsSL", "--retry", "5", "--retry-all-errors", "--retry-delay", "3", "--connect-timeout", "30",
        "-o", contract.gradleArchive, contract.gradleUrl])
      .withNewFile("/opt/gradle-dist/checksums", `${contract.gradleSha256}  ${contract.gradleArchive}\n`)
      .withExec(["sha256sum", "--check", "--status", "/opt/gradle-dist/checksums"])
    return this.runtimeEnvironment(this.caches(container))
  }

  private async contract(): Promise<ToolchainContract> {
    const contract = this.toolchainContract
    if (contract.schemaVersion !== 2) throw new Error("Unsupported Dependeasy toolchain schema")
    if (!["linux/amd64", "linux/arm64"].includes(this.environment.DEPE_CONTAINER_PLATFORM))
      throw new Error("Unsupported or missing Dependeasy container platform")
    return contract
  }

  private runtimeEnvironment(container: Container): Container {
    return Object.entries(this.environment).reduce((current, [key, value]) => current.withEnvVariable(key, value), container)
  }

  private caches(container: Container): Container {
    return Object.entries(this.toolchainContract.caches).reduce((current, [path, name]) =>
      current.withMountedCache(path, dag.cacheVolume(name.replace("{platform}", this.environment.DEPE_CONTAINER_PLATFORM)),
        { sharing: CacheSharingMode.Locked }), container)
  }

  private files(container: Container): Container {
    return Object.entries(this.toolchainContract.files).reduce((current, [path, contents]) =>
      current.withNewFile(path, contents), container)
  }

  async androidToolchain(): Promise<Container> {
    const sdk = (await this.contract()).androidSdk
    return (await this.toolchain())
      .withEnvVariable("ANDROID_SDK_ROOT", sdk.directory)
      .withEnvVariable("ANDROID_HOME", sdk.directory)
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
    const prepared = await this.androidToolchain()
    return this.files(this.caches(prepared.withDirectory("/work/reaktor", this.reaktor)
      .withDirectory(`/work/${this.buildName}`, this.source))).withWorkdir(`/work/${this.buildName}`)
  }

  async browserWorkspace(workspace: Container): Promise<Container> {
    const sdk = (await this.contract()).androidSdk
    const prepared = workspace.withExec([
      "./gradlew", "javascriptToolchain", "pnpmInstall", "--console=plain",
    ])
    const browser = dag.container({ platform: this.environment.DEPE_CONTAINER_PLATFORM as Platform }).from(this.toolchainContract.browserImage)
      .withDirectory("/work", prepared.directory("/work"))
      .withDirectory("/opt/java/openjdk", prepared.directory("/opt/java/openjdk"))
      .withDirectory("/opt/gradle-dist", prepared.directory("/opt/gradle-dist"))
      .withEnvVariable("JAVA_HOME", "/opt/java/openjdk")
      .withEnvVariable("ANDROID_HOME", sdk.directory)
      .withEnvVariable("ANDROID_SDK_ROOT", sdk.directory)
      .withEnvVariable("PATH", `/work/${this.buildName}/build/dependeasy/tools/javascript/bin:/opt/java/openjdk/bin:$PATH`, { expand: true })
      .withWorkdir(`/work/${this.buildName}`)
    return this.runtimeEnvironment(this.files(this.caches(browser)))
  }
}

type Pipeline = {
  schema: number
  build: string
  engineVersion: string
  environment: Record<string, string>
  toolchain: ToolchainContract
  resolve: string[]
  invocationFile: string
}
type Invocation = {
  platform: string
  runner: string
  arguments: string[]
  secrets: string[]
  artifacts: string[]
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
    if (value.schema !== 2 || !/^[a-zA-Z0-9_-]+$/.test(value.build)) throw new Error("Unsupported Dependeasy pipeline")
    return value
  }
  @func()
  async run(target: string, environment: string, secrets: Secret[] = []): Promise<Directory> {
    const pipeline = await this.pipeline()
    const names = await Promise.all(secrets.map(secret => secret.name()))
    const inputs: Record<string, string> = { node: target, environment, secrets: names.join(",") }
    const resolve = pipeline.resolve.map(argument => argument.replace(/\{(node|environment|secrets)\}/g, (_, key: string) => inputs[key]))
    const runtime = new BuildRuntime(this.source, this.reaktor, pipeline.toolchain, pipeline.build, pipeline.environment)
    let container = (await runtime.workspace()).withExec(resolve)
    const invocation: Invocation = JSON.parse(await container.file(pipeline.invocationFile).contents())
    if (invocation.platform !== pipeline.environment.DEPE_CONTAINER_PLATFORM) throw new Error("Container toolchain host differs from Kotlin invocation")
    if (invocation.runner === "browser") container = await runtime.browserWorkspace(container)
    for (const name of invocation.secrets) container = container.withSecretVariable(name, secrets[names.indexOf(name)])
    const completed = container.withExec(invocation.arguments, { expect: ReturnType.Any })
    return dag.directory().withDirectory(".", completed.directory("/work"), { include: invocation.artifacts })
  }
}
