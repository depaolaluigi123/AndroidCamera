/*
 * acus_driver — userspace USB Device driver for AndroidCamera.
 *
 * Receives binary ACUS frames from the phone over ADB forward (USB debug)
 * and feeds a v4l2loopback node so the phone appears as a real webcam in OBS.
 *
 * The phone listens on 127.0.0.1:PORT; we map host localhost:PORT → device:PORT
 * with `adb forward` (more reliable than `adb reverse` on many ROMs).
 *
 * Usage:
 *   acus_driver --probe --serial SERIAL --port 8080
 *   acus_driver --serial SERIAL --port 8080 --device /dev/videoN
 */

#define _GNU_SOURCE
#include "acus_protocol.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <linux/videodev2.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static volatile sig_atomic_t g_running = 1;

static void on_signal(int sig)
{
    (void)sig;
    g_running = 0;
}

static uint16_t rd_be16(const uint8_t *p)
{
    return (uint16_t)((p[0] << 8) | p[1]);
}

static uint32_t rd_be32(const uint8_t *p)
{
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static int read_full(int fd, void *buf, size_t len)
{
    uint8_t *p = buf;
    size_t got = 0;
    while (got < len) {
        ssize_t n = read(fd, p + got, len - got);
        if (n == 0)
            return -1;
        if (n < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        got += (size_t)n;
    }
    return 0;
}

static int write_full(int fd, const void *buf, size_t len)
{
    const uint8_t *p = buf;
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = write(fd, p + sent, len - sent);
        if (n < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        sent += (size_t)n;
    }
    return 0;
}

static const char *find_adb(void)
{
    static char path[512];
    const char *env = getenv("ANDROID_HOME");
    if (!env || !env[0])
        env = getenv("ANDROID_SDK_ROOT");
    if (env && env[0]) {
        snprintf(path, sizeof(path), "%s/platform-tools/adb", env);
        if (access(path, X_OK) == 0)
            return path;
    }
    snprintf(path, sizeof(path), "%s/Android/Sdk/platform-tools/adb", getenv("HOME") ? getenv("HOME") : "");
    if (access(path, X_OK) == 0)
        return path;
    if (access("/usr/bin/adb", X_OK) == 0)
        return "/usr/bin/adb";
    if (access("/usr/local/bin/adb", X_OK) == 0)
        return "/usr/local/bin/adb";
    return "adb";
}

static int run_adb(const char *adb, char *const argv[])
{
    pid_t pid = fork();
    if (pid < 0)
        return -1;
    if (pid == 0) {
        execvp(adb, argv);
        _exit(127);
    }
    int status = 0;
    if (waitpid(pid, &status, 0) < 0)
        return -1;
    if (WIFEXITED(status))
        return WEXITSTATUS(status);
    return -1;
}

static int adb_forward(const char *adb, const char *serial, int port, bool remove)
{
    char port_arg[64];
    snprintf(port_arg, sizeof(port_arg), "tcp:%d", port);
    if (remove) {
        char *argv[] = {
            (char *)adb, "-s", (char *)serial, "forward", "--remove", port_arg, NULL
        };
        return run_adb(adb, argv);
    }
    /* host:port → device:port (phone ACUS server) */
    char *argv[] = {
        (char *)adb, "-s", (char *)serial, "forward", port_arg, port_arg, NULL
    };
    return run_adb(adb, argv);
}

static int connect_localhost(int port)
{
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0)
        return -1;
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

typedef struct {
    uint16_t width;
    uint16_t height;
    uint16_t fps;
    uint16_t rotation;
    bool jpeg;
    uint8_t jpeg_quality;  /* 10..100; meaningful iff jpeg */
    char name[256];
} acus_info_t;

static int read_handshake(int fd, acus_info_t *info)
{
    uint8_t prefix[4 + 1 + 1 + 2 + 2 + 2 + 2 + 1];
    if (read_full(fd, prefix, sizeof(prefix)) != 0)
        return -1;
    if (memcmp(prefix, ACUS_MAGIC, 4) != 0) {
        fprintf(stderr, "Invalid ACUS magic\n");
        return -1;
    }
    uint8_t version = prefix[4];
    /* ACUS Protocol v1. The version byte is always 1. Refuse anything else
     * so a stale phone/app pairing fails loudly. */
    if (version != ACUS_VERSION) {
        fprintf(stderr, "Unsupported ACUS version %u (expected %d)\n",
                version, ACUS_VERSION);
        return -1;
    }
    info->width = rd_be16(prefix + 6);
    info->height = rd_be16(prefix + 8);
    info->fps = rd_be16(prefix + 10);
    info->rotation = rd_be16(prefix + 12);
    info->jpeg = false;
    info->jpeg_quality = ACUS_DEFAULT_JPEG_QUALITY;

    uint8_t flags = prefix[5];
    uint8_t name_len = prefix[14];
    {
        size_t max_name = sizeof(info->name) - 1;
        if ((size_t)name_len > max_name)
            name_len = (uint8_t)max_name;
    }
    if (name_len > 0) {
        if (read_full(fd, info->name, name_len) != 0)
            return -1;
    }
    info->name[name_len] = '\0';

    /* Trailing bytes are FLAG-DRIVEN (not a fixed pair): exactly 0/1 bytes
     * depending on the JPEG flag. We never read a fixed 1 byte because that
     * desynchronises the receiver for raw YUV streams (flags=0, no trailing
     * bytes) which is the common case.
     *
     *   FLAG_JPEG set → 1 byte  jpeg_quality (10..100)
     *   otherwise    → 0 bytes
     */
    uint8_t extra[1];
    int n_extra = 0;
    if (flags & ACUS_FLAG_JPEG)
        extra[n_extra++] = 0;  /* placeholder, overwritten below */
    if (n_extra > 0) {
        if (read_full(fd, extra, (size_t)n_extra) != 0) {
            fprintf(stderr, "Failed to read ACUS trailing bytes\n");
            return -1;
        }
    }
    if (flags & ACUS_FLAG_JPEG) {
        uint8_t q = extra[0];
        if (q >= 10 && q <= 100)
            info->jpeg_quality = q;
        info->jpeg = true;
    }

    if (info->width == 0 || info->height == 0) {
        fprintf(stderr, "Invalid handshake size %ux%u\n", info->width, info->height);
        return -1;
    }
    return 0;
}

static int v4l2_open_and_format(const char *device, int width, int height, int fps, bool jpeg)
{
    int fd = open(device, O_RDWR | O_NONBLOCK);
    if (fd < 0) {
        /* Some loopback nodes prefer blocking write. */
        fd = open(device, O_RDWR);
    }
    if (fd < 0) {
        perror(device);
        return -1;
    }

    /* Clear nonblock for steady write() pacing. */
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0)
        fcntl(fd, F_SETFL, flags & ~O_NONBLOCK);

    /* Choose the V4L2 pixel format. JPEG multi-frame (MJPG) requires the
     * loopback to accept V4L2_PIX_FMT_MJPEG; we fall back to V4L2_PIX_FMT_JPEG
     * for single-frame streams when the loopback does not advertise MJPEG. */
    if (jpeg) {
        struct v4l2_format fmt;
        memset(&fmt, 0, sizeof(fmt));
        fmt.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
        fmt.fmt.pix.width = (uint32_t)width;
        fmt.fmt.pix.height = (uint32_t)height;
        fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_MJPEG;
        fmt.fmt.pix.field = V4L2_FIELD_NONE;
        fmt.fmt.pix.sizeimage = (uint32_t)(width * height * 4);  /* generous cap */
        if (ioctl(fd, VIDIOC_S_FMT, &fmt) == 0)
            goto set_parm;
        memset(&fmt, 0, sizeof(fmt));
        fmt.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
        fmt.fmt.pix.width = (uint32_t)width;
        fmt.fmt.pix.height = (uint32_t)height;
        fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_JPEG;
        fmt.fmt.pix.field = V4L2_FIELD_NONE;
        fmt.fmt.pix.sizeimage = (uint32_t)(width * height * 4);
        if (ioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
            perror("VIDIOC_S_FMT (jpeg)");
            close(fd);
            return -1;
        }
      set_parm:
        if (fps > 0) {
            struct v4l2_streamparm parm;
            memset(&parm, 0, sizeof(parm));
            parm.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
            parm.parm.output.timeperframe.numerator = 1;
            parm.parm.output.timeperframe.denominator = (uint32_t)fps;
            if (ioctl(fd, VIDIOC_S_PARM, &parm) < 0) {
                memset(&parm, 0, sizeof(parm));
                parm.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
                parm.parm.capture.timeperframe.numerator = 1;
                parm.parm.capture.timeperframe.denominator = (uint32_t)fps;
                ioctl(fd, VIDIOC_S_PARM, &parm);
            }
        }
        return fd;
    }

    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    fmt.fmt.pix.width = (uint32_t)width;
    fmt.fmt.pix.height = (uint32_t)height;
    fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_YUV420;
    fmt.fmt.pix.field = V4L2_FIELD_NONE;
    fmt.fmt.pix.bytesperline = (uint32_t)width;
    fmt.fmt.pix.sizeimage = (uint32_t)(width * height * 3 / 2);
    if (ioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
        /* Fallback: some builds expose CAPTURE for loopback writers. */
        memset(&fmt, 0, sizeof(fmt));
        fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        fmt.fmt.pix.width = (uint32_t)width;
        fmt.fmt.pix.height = (uint32_t)height;
        fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_YUV420;
        fmt.fmt.pix.field = V4L2_FIELD_NONE;
        fmt.fmt.pix.sizeimage = (uint32_t)(width * height * 3 / 2);
        if (ioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
            perror("VIDIOC_S_FMT");
            close(fd);
            return -1;
        }
    }

    if (fps > 0) {
        struct v4l2_streamparm parm;
        memset(&parm, 0, sizeof(parm));
        parm.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
        parm.parm.output.timeperframe.numerator = 1;
        parm.parm.output.timeperframe.denominator = (uint32_t)fps;
        if (ioctl(fd, VIDIOC_S_PARM, &parm) < 0) {
            memset(&parm, 0, sizeof(parm));
            parm.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
            parm.parm.capture.timeperframe.numerator = 1;
            parm.parm.capture.timeperframe.denominator = (uint32_t)fps;
            ioctl(fd, VIDIOC_S_PARM, &parm);
        }
    }
    return fd;
}

static void usage(const char *argv0)
{
    fprintf(stderr,
            "Usage:\n"
            "  %s --probe --serial SERIAL [--port PORT] [--adb PATH]\n"
            "  %s --serial SERIAL --device /dev/videoN [--port PORT] [--adb PATH]\n"
            "\n"
            "Options:\n"
            "  --probe          Read handshake and print JSON, then exit\n"
            "  --serial SERIAL  ADB device serial\n"
            "  --port PORT      Phone ACUS port (default 8080)\n"
            "  --device PATH    v4l2loopback device to feed\n"
            "  --adb PATH       adb binary (default: auto)\n"
            "  --no-forward     Do not run adb forward\n"
            "  --host HOST      Connect host (default 127.0.0.1)\n"
            "\n"
            "The driver negotiates ACUS Protocol v1 with the phone; JPEG\n"
            "compression is picked up automatically and the v4l2loopback\n"
            "node is configured accordingly (V4L2_PIX_FMT_MJPEG for JPEG\n"
            "streams).\n",
            argv0, argv0);
}

int main(int argc, char **argv)
{
    const char *serial = NULL;
    const char *device = NULL;
    const char *adb = NULL;
    const char *host = "127.0.0.1";
    int port = 8080;
    bool probe = false;
    bool do_forward = true;

    static struct option long_opts[] = {
        {"probe", no_argument, 0, 'P'},
        {"serial", required_argument, 0, 's'},
        {"port", required_argument, 0, 'p'},
        {"device", required_argument, 0, 'd'},
        {"adb", required_argument, 0, 'a'},
        {"no-forward", no_argument, 0, 'n'},
        {"no-reverse", no_argument, 0, 'n'}, /* alias */
        {"host", required_argument, 0, 'H'},
        {"help", no_argument, 0, 'h'},
        {0, 0, 0, 0}
    };

    int opt;
    while ((opt = getopt_long(argc, argv, "Ps:p:d:a:nH:h", long_opts, NULL)) != -1) {
        switch (opt) {
        case 'P':
            probe = true;
            break;
        case 's':
            serial = optarg;
            break;
        case 'p':
            port = atoi(optarg);
            break;
        case 'd':
            device = optarg;
            break;
        case 'a':
            adb = optarg;
            break;
        case 'n':
            do_forward = false;
            break;
        case 'H':
            host = optarg;
            break;
        case 'h':
        default:
            usage(argv[0]);
            return opt == 'h' ? 0 : 1;
        }
    }

    if (!serial || port <= 0 || port > 65535) {
        usage(argv[0]);
        return 1;
    }
    if (!probe && (!device || !device[0])) {
        usage(argv[0]);
        return 1;
    }
    if (!adb)
        adb = find_adb();

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    if (do_forward) {
        if (adb_forward(adb, serial, port, false) != 0) {
            fprintf(stderr, "adb forward failed (adb=%s serial=%s port=%d)\n",
                    adb, serial, port);
            return 2;
        }
    }

    /* Retry connect briefly — phone service may still be binding. */
    int sock = -1;
    int max_attempts = probe ? 8 : 40;
    for (int attempt = 0; attempt < max_attempts && g_running; attempt++) {
        sock = connect_localhost(port);
        if (sock >= 0)
            break;
        usleep(probe ? 100000 : 250000);
    }
    if (sock < 0) {
        fprintf(stderr, "Cannot connect to %s:%d via ADB forward. "
                        "Is the phone streaming in USB Device mode?\n",
                host, port);
        return 3;
    }

    /* Fail fast when an HTTP server is listening instead of ACUS. */
    {
        struct timeval tv;
        tv.tv_sec = probe ? 1 : 5;
        tv.tv_usec = 0;
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    }

    acus_info_t info;
    memset(&info, 0, sizeof(info));
    if (read_handshake(sock, &info) != 0) {
        fprintf(stderr, "ACUS handshake failed\n");
        close(sock);
        return 4;
    }

    if (probe) {
        const char *pix = info.jpeg ? "mjpeg" : "yuv420p";
        const char *fmt = info.jpeg ? "JPEG" : "YUV";
        /* jpeg_quality is meaningful iff JPEG; for YUV the phone sends 0 and
         * the GUI shows "-". */
        unsigned short jpeg_q = info.jpeg ? info.jpeg_quality : 0;
        printf(
            "{\"deviceName\":\"%s\",\"width\":%u,\"height\":%u,"
            "\"outputWidth\":%u,\"outputHeight\":%u,\"fps\":%u,"
            "\"rotation\":%u,\"pixelFormat\":\"%s\",\"format\":\"%s\","
            "\"ready\":true,\"transport\":\"acus\","
            "\"protocol_version\":%d,"
            "\"jpeg_supported\":%s,"
            "\"jpeg_quality\":%u}\n",
            info.name, info.width, info.height, info.width, info.height,
            info.fps ? info.fps : 30, info.rotation, pix, fmt,
            ACUS_VERSION,
            info.jpeg ? "true" : "false",
            jpeg_q);
        fflush(stdout);
        close(sock);
        return 0;
    }

    {
        const char *jpeg_tag = info.jpeg ? " (JPEG q=%u)" : "";
        fprintf(stderr,
                "ACUS connected: %s %ux%u@%u → %s",
                info.name, info.width, info.height,
                info.fps ? info.fps : 30, device);
        if (info.jpeg)
            fprintf(stderr, jpeg_tag, info.jpeg_quality);
        fprintf(stderr, "\n");
    }

    int vfd = v4l2_open_and_format(device, info.width, info.height,
                                   info.fps ? info.fps : 30, info.jpeg);
    if (vfd < 0) {
        close(sock);
        return 5;
    }

    /* YUV frames are fixed size (w*h*3/2). JPEG frames vary — we pick a
     * generous upper bound (8 MB) that still rejects nonsense traffic. */
    const size_t expected = (size_t)info.width * (size_t)info.height * 3 / 2;
    const size_t max_frame =
        info.jpeg ? (8u * 1024u * 1024u) : (expected + 1024u * 1024u);
    uint8_t hdr[12];
    uint8_t *frame = malloc(expected + 4096);
    if (!frame) {
        close(vfd);
        close(sock);
        return 6;
    }

    while (g_running) {
        if (read_full(sock, hdr, sizeof(hdr)) != 0)
            break;
        if (memcmp(hdr, FRME_MAGIC, 4) != 0) {
            fprintf(stderr, "Bad frame magic\n");
            break;
        }
        uint32_t seq = rd_be32(hdr + 4);
        uint32_t size = rd_be32(hdr + 8);
        (void)seq;
        if (size == 0 || size > max_frame) {
            fprintf(stderr, "Invalid frame size %u\n", size);
            break;
        }
        if (size > expected + 4096) {
            uint8_t *nbuf = realloc(frame, size);
            if (!nbuf)
                break;
            frame = nbuf;
        }
        if (read_full(sock, frame, size) != 0)
            break;

        size_t write_size = size;
        /* For YUV we clamp writes to the exact I420 size in case the phone
         * mistakenly appended alignment bytes. For JPEG we forward the whole
         * payload as-is: the loopback node is MJPEG-aware and we trust the
         * size reported in the FRME header. */
        if (!info.jpeg && write_size != expected) {
            if (write_size > expected)
                write_size = expected;
        }
        if (write_full(vfd, frame, write_size) != 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                usleep(2000);
                continue;
            }
            /* Device busy (e.g. format change) — keep trying briefly. */
            usleep(5000);
        }
    }

    free(frame);
    close(vfd);
    close(sock);
    fprintf(stderr, "acus_driver stopped\n");
    return 0;
}
