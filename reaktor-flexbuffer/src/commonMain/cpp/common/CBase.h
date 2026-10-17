#pragma once

#include <stddef.h>
#include <stdint.h>

#define repeat(i, n) for (size_t i = 0; (i) < (n); ++(i))
#define range(start, i, end) for (int i = (start); ((start) < (end)) ? ((i) <= (end)) : ((i) >= (end)); ((start) < (end)) ? ++(i) : --(i))
