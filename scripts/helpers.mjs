export function trim(strings, ...values) {
    let result = '';
    strings.forEach((string, i) => {
        result += string;
        if (i < values.length) {
            result += values[i];
        }
    });
    return result.trim();
}

export const capitalize = (str) => str.charAt(0).toUpperCase() + str.slice(1)

export async function once(path, commandPromise) {
    try {
        await fs.access(path)
    } catch (e) {
        return await commandPromise()
    }
    return null
}

export async function writeOnce(filePath, content) {
    let data
    if (typeof content === "function")
        data = await content()
    else
        data = content

    return await once(filePath,() => fs.writeFile(filePath, data))
}

// Move all these to TreeBuilder
