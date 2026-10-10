export function getShibasisUserAgent(): string {
    if (typeof window !== 'undefined' && window.navigator) {
        return window.navigator.userAgent;
    }
    return "Unknown";
}