#include <sqlite3.h>
// Apple's device SQLite omits these entry points referenced by unused SQLiter cache wrappers.
int sqlite3_enable_load_extension(sqlite3 *db, int enabled) {
    (void)db; (void)enabled;
    return SQLITE_ERROR;
}
int sqlite3_load_extension(sqlite3 *db, const char *file, const char *entry, char **error) {
    (void)db; (void)file; (void)entry;
    if (error) *error = sqlite3_mprintf("Dynamic SQLite extensions are unavailable in the WebView test host");
    return SQLITE_ERROR;
}
