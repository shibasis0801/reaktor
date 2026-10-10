#pragma once

#include <chrono>
#include <functional>
#include <string>
#include <utility>

namespace reaktor::performance {

using Clock = std::chrono::steady_clock;
using TimerCallback = std::function<void(const std::string& name, double duration_ms)>;

inline double now_ms() {
  const auto now = Clock::now().time_since_epoch();
  return std::chrono::duration<double, std::milli>(now).count();
}

class ScopedTimer {
 public:
  ScopedTimer(std::string name, TimerCallback callback)
      : name_(std::move(name)), callback_(std::move(callback)), started_at_(Clock::now()) {}

  ScopedTimer(const ScopedTimer&) = delete;
  ScopedTimer& operator=(const ScopedTimer&) = delete;

  ScopedTimer(ScopedTimer&& other) noexcept
      : name_(std::move(other.name_)),
        callback_(std::move(other.callback_)),
        started_at_(other.started_at_),
        active_(other.active_) {
    other.active_ = false;
  }

  ScopedTimer& operator=(ScopedTimer&& other) noexcept {
    if (this != &other) {
      finish();
      name_ = std::move(other.name_);
      callback_ = std::move(other.callback_);
      started_at_ = other.started_at_;
      active_ = other.active_;
      other.active_ = false;
    }
    return *this;
  }

  ~ScopedTimer() { finish(); }

  void finish() noexcept {
    if (!active_) return;
    active_ = false;
    if (!callback_) return;
    const auto elapsed = Clock::now() - started_at_;
    callback_(name_, std::chrono::duration<double, std::milli>(elapsed).count());
  }

 private:
  std::string name_;
  TimerCallback callback_;
  Clock::time_point started_at_;
  bool active_ = true;
};

}  // namespace reaktor::performance
