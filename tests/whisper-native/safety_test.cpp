#include "whisper_safety.h"
#include <cassert>
#include <iostream>
#include <stdexcept>
using namespace nottheomi;
int main() {
    const uint16_t path[] = {'c', 0x00e9, 0xd83d, 0xde00};
    assert(path_utf8(path, 4) == "c\xc3\xa9\xf0\x9f\x98\x80");
    assert(text_utf16("c\xc3\xa9\xf0\x9f\x98\x80") == std::vector<uint16_t>(path, path + 4));
    for (std::vector<uint16_t> invalid : {std::vector<uint16_t>{0}, {0xd800}, {0xdc00}, {0xd800, 'x'}}) {
        bool rejected = false;
        try { path_utf8(invalid.data(), invalid.size()); }
        catch (const std::invalid_argument &) { rejected = true; }
        assert(rejected);
    }
    for (const auto &invalid : {"\xc0\x80", "\xed\xa0\x80", "\xf4\x90\x80\x80", "\xf0\x9f", "\xff", "\xe0\x80\x80"}) {
        const auto result = text_utf16(invalid);
        assert(!result.empty());
        for (auto ch : result) assert(ch == 0xfffd);
    }
    assert(text_utf16(std::string("a\0b", 3)) == std::vector<uint16_t>({'a', 0, 'b'}));
    std::vector<int16_t> pcm = {-32768, 123, 32767};
    std::vector<float> floats = {-1.0f, 0.5f, 1.0f};
    std::string private_text = "sensitive";
    wipe(pcm); wipe(floats); wipe(private_text);
    for (auto value : pcm) assert(value == 0);
    for (auto value : floats) assert(value == 0);
    for (auto value : private_text) assert(value == 0);
    assert(kMaxSamples == 480000);
    std::cout << "C++ Unicode/bounds/wipe checks passed\n";
}
