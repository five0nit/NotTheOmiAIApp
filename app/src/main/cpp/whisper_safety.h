#pragma once
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace nottheomi {
constexpr int kSampleRate = 16000;
constexpr int kMaxSamples = kSampleRate * 30;
constexpr size_t kMaxTextBytes = 32768;

// Volatile stores are intentional: ordinary memset may be optimized away.
template<class Container> void wipe(Container &values) noexcept {
    using T = typename Container::value_type;
    volatile T *data = values.data();
    for (size_t i = 0; i < values.size(); ++i) data[i] = 0;
}
template<class Container> struct Wiped {
    Container value;
    ~Wiped() { wipe(value); }
};

// Java strings use UTF-16; POSIX paths and Whisper text use standard UTF-8.
// JNI NewStringUTF/GetStringUTFChars instead use *modified* UTF-8.
inline std::string path_utf8(const uint16_t *text, size_t size) {
    std::string out;
    out.reserve(size * 3);
    for (size_t i = 0; i < size; ++i) {
        uint32_t cp = text[i];
        if (cp == 0) throw std::invalid_argument("NUL in model path");
        if (cp >= 0xd800 && cp <= 0xdbff) {
            if (++i >= size || text[i] < 0xdc00 || text[i] > 0xdfff)
                throw std::invalid_argument("Invalid model path encoding");
            cp = 0x10000 + ((cp - 0xd800) << 10) + (text[i] - 0xdc00);
        } else if (cp >= 0xdc00 && cp <= 0xdfff) {
            throw std::invalid_argument("Invalid model path encoding");
        }
        if (cp < 0x80) out.push_back(static_cast<char>(cp));
        else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xc0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 63)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xe0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 63)));
            out.push_back(static_cast<char>(0x80 | (cp & 63)));
        } else {
            out.push_back(static_cast<char>(0xf0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 63)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 63)));
            out.push_back(static_cast<char>(0x80 | (cp & 63)));
        }
    }
    return out;
}

// Reject overlong sequences, surrogate codepoints, and values > U+10FFFF.
// Malformed model text becomes U+FFFD, never a malformed Java string.
inline std::vector<uint16_t> text_utf16(std::string_view text) {
    std::vector<uint16_t> out;
    out.reserve(text.size());
    for (size_t i = 0; i < text.size();) {
        const auto lead = static_cast<uint8_t>(text[i]);
        uint32_t cp = lead;
        size_t length = 1;
        uint32_t minimum = 0;
        if (lead >= 0xc2 && lead <= 0xdf) { cp = lead & 31; length = 2; minimum = 0x80; }
        else if (lead >= 0xe0 && lead <= 0xef) { cp = lead & 15; length = 3; minimum = 0x800; }
        else if (lead >= 0xf0 && lead <= 0xf4) { cp = lead & 7; length = 4; minimum = 0x10000; }
        else if (lead >= 0x80) { cp = 0xfffd; }
        bool valid = i + length <= text.size();
        for (size_t j = 1; valid && j < length; ++j) {
            const auto next = static_cast<uint8_t>(text[i + j]);
            valid = (next & 0xc0) == 0x80;
            cp = (cp << 6) | (next & 63);
        }
        if (!valid || cp < minimum || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) {
            cp = 0xfffd;
            length = 1;
        }
        i += length;
        if (cp < 0x10000) out.push_back(static_cast<uint16_t>(cp));
        else {
            cp -= 0x10000;
            out.push_back(static_cast<uint16_t>(0xd800 + (cp >> 10)));
            out.push_back(static_cast<uint16_t>(0xdc00 + (cp & 1023)));
        }
    }
    return out;
}
} // namespace nottheomi
