#include "gdxvk_deletion.h"

#include <utility>

namespace gdxvk {

void DeletionQueue::retire(std::function<void()> destroy, uint64_t extraSerials) {
    // The tag is read at the retire call, so it is never newer than the first submit that could follow it.
    const uint64_t serial = timeline_.nextSerial() + extraSerials;
    std::lock_guard<std::mutex> lock(mutex_);
    items_.push_back({serial, std::move(destroy)});
    ++retired_;
}

size_t DeletionQueue::collect() {
    const uint64_t done = timeline_.completed();
    std::vector<Item> ready;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        size_t keep = 0;
        for (size_t i = 0; i < items_.size(); ++i) {
            if (items_[i].serial <= done) ready.push_back(std::move(items_[i]));
            else items_[keep++] = std::move(items_[i]);
        }
        items_.resize(keep);
    }
    for (Item& it : ready) it.destroy(); // outside the lock: destructors may retire more
    return ready.size();
}

void DeletionQueue::flushAll() {
    for (;;) {
        std::vector<Item> all;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            all.swap(items_);
        }
        if (all.empty()) return;
        for (Item& it : all) it.destroy();
    }
}

size_t DeletionQueue::pending() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return items_.size();
}

} // namespace gdxvk
