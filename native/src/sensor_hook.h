#pragma once

#include <string>

namespace il {

/** Installs the native sensor hooks (idempotent). Returns true when the hook is live. */
bool installSensorHooks();

/** True once `installSensorHooks()` succeeded in this process. */
bool hooksInstalled();

/** Why the last install attempt failed (empty when it succeeded). */
const std::string &lastInstallError();

}  // namespace il
