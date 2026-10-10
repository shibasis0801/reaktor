#pragma once
// Narrow construction hooks applied to the pinned upstream source by CMake.
// They configure public engine profiles before a browser is created.
#ifdef __cplusplus
extern "C" {
#endif
void reaktor_web_configure_apple(void *configuration);
void *reaktor_web_create_gtk(void);
long reaktor_web_create_windows_controller(void *environment, void *window, void *callback);
const wchar_t *reaktor_web_windows_data_folder(void);
#ifdef __cplusplus
}
#endif
