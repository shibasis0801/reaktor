#pragma once

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ReaktorInteropRuntime ReaktorInteropRuntime;
typedef struct ReaktorInteropResult ReaktorInteropResult;
typedef ReaktorInteropResult* (*ReaktorHostDispatch)(void* context, const char* module, const char* operation, const char* request);

/* Each result is caller-owned. Callbacks transfer their result to the runtime. */
ReaktorInteropResult* ReaktorInterop_success(const char* value);
ReaktorInteropResult* ReaktorInterop_failure(const char* error);
const char* ReaktorInterop_value(const ReaktorInteropResult* result);
const char* ReaktorInterop_error(const ReaktorInteropResult* result);
void ReaktorInterop_release(ReaktorInteropResult* result);

/* Runtimes are thread-confined. Close on the creating thread, outside callbacks. */
ReaktorInteropRuntime* ReaktorInterop_create(ReaktorHostDispatch dispatch, void* context);
ReaktorInteropResult* ReaktorInterop_close(ReaktorInteropRuntime* runtime);
ReaktorInteropResult* ReaktorInterop_native(ReaktorInteropRuntime* runtime, const char* module, const char* operation, const char* request);
ReaktorInteropResult* ReaktorInterop_typescript(ReaktorInteropRuntime* runtime, const char* module, const char* operation, const char* request);
ReaktorInteropResult* ReaktorInterop_evaluate(ReaktorInteropRuntime* runtime, const char* source, const char* sourceName);
ReaktorInteropResult* ReaktorInterop_export(ReaktorInteropRuntime* runtime, const char* module, const char* operation, ReaktorHostDispatch dispatch, void* context);

#ifdef __cplusplus
}
#endif
