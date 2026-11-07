#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace subtitle_native::opencl_q6 {
constexpr size_t block_bytes = 210;
constexpr size_t chunk_blocks = 32768;
constexpr size_t stream_threshold = 16 * 1024 * 1024;
// Byte-exact AoS -> SoA conversion matching the pinned cvt.cl kernels.
// No floating-point decoding or requantization occurs. The caller owns bounded
// buffers of 128/64/16/2 bytes per block, then uploads those four planes.
inline void pack(const uint8_t * source, size_t blocks, bool noshuffle,
                 uint8_t * ql, uint8_t * qh, uint8_t * scales, uint8_t * d) {
    for (size_t block = 0; block < blocks; ++block) {
        const uint8_t * b = source + block * block_bytes;
        uint8_t * lo = ql + block * 128;
        uint8_t * hi = qh + block * 64;
        if (!noshuffle) {
            std::memcpy(lo, b, 128); std::memcpy(hi, b + 128, 64);
        } else {
            for (size_t i = 0; i < 32; ++i) {
                const uint8_t x0 = b[i * 2], x1 = b[i * 2 + 1];
                const uint8_t x2 = b[i * 2 + 64], x3 = b[i * 2 + 65];
                lo[i] = (x0 & 15) | ((x1 & 15) << 4);
                lo[i + 32] = (x0 >> 4) | (x1 & 240);
                lo[i + 64] = (x2 & 15) | ((x3 & 15) << 4);
                lo[i + 96] = (x2 >> 4) | (x3 & 240);
            }
            for (size_t half = 0; half < 2; ++half) {
                for (size_t i = 0; i < 8; ++i) {
                    for (size_t pair = 0; pair < 4; ++pair) {
                        uint8_t value = 0;
                        for (size_t lane = 0; lane < 4; ++lane)
                            value |= ((b[128 + half * 32 + i * 4 + lane] >> (pair * 2)) & 3) << (lane * 2);
                        hi[half * 32 + pair * 8 + i] = value;
                    }
                }
            }
        }
        std::memcpy(scales + block * 16, b + 192, 16);
        std::memcpy(d + block * 2, b + 208, 2);
    }
}
}
