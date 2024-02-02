// todo add workerMain and desktopMain support
// todo add testing support, generate integration test directories
export const sourceSets = (moduleType = "full") => {
    switch(moduleType) {
        case "full": return ["commonMain", "droidMain", "darwinMain", "webMain", "serverMain"]
        case "client": return ["commonMain", "droidMain", "darwinMain", "webMain"]
        case "react": return ["commonMain", "droidMain", "darwinMain", "webMain", "reactMain"]
        case "server": return ["commonMain", "serverMain"]
        case "native": return ["commonMain", "droidMain", "darwinMain", "cppMain"]
    }
}

export const sourceSetPrefix = {
    "droidMain": "Android",
    "darwinMain": "Darwin",
    "webMain": "Web",
    "serverMain": "Server",
    "workerMain": "Worker"
}
