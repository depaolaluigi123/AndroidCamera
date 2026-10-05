package com.androidcamera.webcam.connection

import com.androidcamera.webcam.model.ConnectionMode
import com.androidcamera.webcam.model.StreamEndpoints
import com.androidcamera.webcam.model.StreamFormat

/**
 * Builds OBS-ready stream endpoints for the selected connection mode.
 */
class ConnectionInfoFactory(
    private val addressResolver: NetworkAddressResolver
) {

    fun buildEndpoints(
        mode: ConnectionMode,
        basePort: Int
    ): StreamEndpoints {
        return when (mode) {
            ConnectionMode.USB_DEVICE -> StreamEndpoints(
                host = "127.0.0.1",
                port = basePort,
                // Binary ACUS endpoint consumed by AndroidCameraUsbDriver (not HTTP).
                streamUrl = "acus://127.0.0.1:$basePort",
                format = StreamFormat.YUV
            )
            ConnectionMode.USB -> {
                val host = addressResolver.resolveHost(preferLocalhost = true)
                StreamEndpoints(
                    host = host,
                    port = basePort,
                    // /stream.video is the canonical alias of /stream.yuv
                    // served by StreamHttpServer; using it on USB Tethering /
                    // IP modes lets the URL stay stable across JPEG/YUV
                    // switching and matches what the Python GUI expects.
                    streamUrl = "http://$host:$basePort/stream.video",
                    format = StreamFormat.YUV
                )
            }
            ConnectionMode.IP -> {
                val host = addressResolver.resolveHost(preferLocalhost = false)
                StreamEndpoints(
                    host = host,
                    port = basePort,
                    streamUrl = "http://$host:$basePort/stream.video",
                    format = StreamFormat.YUV
                )
            }
        }
    }
}
