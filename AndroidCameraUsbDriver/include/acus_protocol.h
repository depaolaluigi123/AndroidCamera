#ifndef ACUS_PROTOCOL_H
#define ACUS_PROTOCOL_H

#include <stdint.h>

#define ACUS_MAGIC "ACUS"
#define FRME_MAGIC "FRME"

/* ACUS Protocol v1. The version byte is always 1. */
#define ACUS_VERSION            1

/* Flag bits (byte 5 of the handshake). Bit 0 marks a JPEG transport.
 * Reserved bits must be sent as zero. */
#define ACUS_FLAG_JPEG          0x01u

/* Default placeholder value used by the phone when the JPEG flag is not
 * set; the PC MUST ignore it in that case. */
#define ACUS_DEFAULT_JPEG_QUALITY   0

#pragma pack(push, 1)
typedef struct {
    char magic[4];      /* "ACUS" */
    uint8_t version;    /* ACUS_VERSION (1) */
    uint8_t flags;      /* ACUS_FLAG_* bitmask */
    uint16_t width;     /* BE */
    uint16_t height;    /* BE */
    uint16_t fps;       /* BE */
    uint16_t rotation;  /* BE */
    uint8_t name_len;
    /* followed by name_len bytes UTF-8 */
    /* Trailing bytes are FLAGGED-DRIVEN (not a fixed-length pair):
     *   jpeg_quality (u8)  — present iff ACUS_FLAG_JPEG is set
     * When the flag is clear the corresponding byte is absent; the client
     * MUST read exactly 0/1 bytes based on flags, never a fixed pair.
     */
} acus_handshake_prefix_t;

typedef struct {
    char magic[4];      /* "FRME" */
    uint32_t sequence;  /* BE */
    uint32_t size;      /* BE */
    /* followed by size bytes of payload.
     * - YUV (flags without ACUS_FLAG_JPEG): payload is I420 of exactly
     *   width * height * 3/2 bytes.
     * - JPEG (flags with ACUS_FLAG_JPEG): payload is one JPEG frame whose
     *   compressed size varies.
     */
} acus_frame_header_t;
#pragma pack(pop)

#endif /* ACUS_PROTOCOL_H */
