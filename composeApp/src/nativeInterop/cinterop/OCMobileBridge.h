#pragma once

#include <stdbool.h>

// Functions implemented in Swift (via @_cdecl) inside the iOS targets.
// These are resolved at link time by the final app/extension binary.

void triggerApnsRegistrationFromSwift(void);
