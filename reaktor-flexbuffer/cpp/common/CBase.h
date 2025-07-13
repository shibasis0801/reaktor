#pragma once

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
typedef int64_t FlexPointer;
#define repeat(i, n) for (size_t i = 0; (i) < (n); ++(i))
#define range(start, i, end) for (int i = start; (start < end) ? ((i) <= (end)) : ((i) >= (start)); (start < end) ? ++(i) : --(i))