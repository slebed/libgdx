// Deferred deletion (plan, Phase 1b). Anything the GPU may still be reading is retired instead of destroyed: it is tagged with
// the serial of the next submit and runs its destructor once Timeline::completed() has reached that serial.
// retire() is thread-safe, so dispose() works from create(), between frames, across windows and from loader threads.
#pragma once

#include <cstdint>
#include <functional>
#include <mutex>
#include <vector>

#include "gdxvk_timeline.h"

namespace gdxvk {

class DeletionQueue {
public:
    explicit DeletionQueue(Timeline& timeline) : timeline_(timeline) {}
    ~DeletionQueue() { flushAll(); }
    DeletionQueue(const DeletionQueue&) = delete;
    DeletionQueue& operator=(const DeletionQueue&) = delete;

    // `destroy` runs on whichever thread calls collect().
    // `extraSerials` delays the free by that many more submits. Use 1 for anything that may still have a copy queued in the
    // staging ring: the copy is recorded into the *next* frame, which can be one submit later than the one being recorded now.
    void retire(std::function<void()> destroy, uint64_t extraSerials = 0);

    // Runs everything the GPU has finished with. Returns how many ran.
    size_t collect();

    // Shutdown: the caller has already waited for the device to go idle.
    void flushAll();

    size_t pending() const;
    uint64_t totalRetired() const { return retired_; }

private:
    struct Item {
        uint64_t serial;
        std::function<void()> destroy;
    };
    Timeline& timeline_;
    mutable std::mutex mutex_;
    std::vector<Item> items_;
    uint64_t retired_ = 0;
};

} // namespace gdxvk
