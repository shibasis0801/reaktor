#pragma once
#include <string>
#include <map>
#include <vector>
#include <stdexcept>
#include "reaktor_web.h"
inline std::string quote(const std::string &text) {
    std::string result = "\"";
    const char hex[] = "0123456789abcdef";
    for (unsigned char c : text) {
        if (c == '"' || c == '\\') { result += '\\'; result += c; }
        else if (c < 32) { result += "\\u00"; result += hex[c >> 4]; result += hex[c & 15]; }
        else result += c;
    }
    return result + '"';
}
struct NativeAsset { std::vector<uint8_t> bytes; std::string mime, csp; };
struct NativeAssets {
    std::map<std::string, NativeAsset> entries;
    size_t size = 0;
    void clear() { entries.clear(); size = 0; }
    void put(const char *path, const char *mime, const uint8_t *bytes, uint32_t length, const char *csp) {
        if (!path || !mime || !csp || (!bytes && length) || length > 8 * 1024 * 1024) throw std::runtime_error("Invalid asset");
        auto old = entries.find(path);
        size_t total = size + length - (old == entries.end() ? 0 : old->second.bytes.size());
        if (total > 64 * 1024 * 1024) throw std::runtime_error("Bundle exceeds 64 MiB");
        std::vector<uint8_t> data;
        if (length) data.assign(bytes, bytes + length);
        entries[path] = {std::move(data), mime, csp};
        size = total;
    }
};
// A decoded manifest path, never a filesystem path.
inline std::string asset_path(std::string path) {
    auto revision = path.find('/', 1);
    if (revision == std::string::npos) return {};
    path = path.substr(revision);
    if (path.empty() || path.find('\\') != std::string::npos || path.find('%') != std::string::npos) return {};
    size_t start = 1;
    while (start < path.size()) {
        auto end = path.find('/', start);
        auto part = path.substr(start, end - start);
        if (part.empty() || part == "." || part == "..") return {};
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return path;
}
