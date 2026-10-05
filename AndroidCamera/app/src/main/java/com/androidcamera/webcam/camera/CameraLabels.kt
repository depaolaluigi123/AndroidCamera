package com.androidcamera.webcam.camera

import android.content.Context
import com.androidcamera.webcam.R
import com.androidcamera.webcam.model.AvailableCamera
import com.androidcamera.webcam.model.CameraFacing
import java.util.Locale

object CameraLabels {

    fun displayName(context: Context, camera: AvailableCamera): String {
        val facing = when (camera.facing) {
            CameraFacing.BACK -> context.getString(R.string.camera_facing_back)
            CameraFacing.FRONT -> context.getString(R.string.camera_facing_front)
            CameraFacing.EXTERNAL -> context.getString(R.string.camera_facing_external)
            CameraFacing.UNKNOWN -> context.getString(R.string.camera_facing_unknown)
        }
        val focal = camera.focalLengthMm?.let {
            String.format(Locale.US, "%.1fmm", it)
        }
        return if (focal != null) {
            context.getString(
                R.string.camera_option_with_focal,
                facing,
                camera.indexAmongFacing,
                focal,
                camera.id
            )
        } else {
            context.getString(
                R.string.camera_option,
                facing,
                camera.indexAmongFacing,
                camera.id
            )
        }
    }

    fun shortName(context: Context, camera: AvailableCamera): String {
        val facing = when (camera.facing) {
            CameraFacing.BACK -> context.getString(R.string.camera_facing_back_short)
            CameraFacing.FRONT -> context.getString(R.string.camera_facing_front_short)
            CameraFacing.EXTERNAL -> context.getString(R.string.camera_facing_external_short)
            CameraFacing.UNKNOWN -> context.getString(R.string.camera_facing_unknown_short)
        }
        return context.getString(
            R.string.camera_in_use_short,
            facing,
            camera.indexAmongFacing,
            camera.id
        )
    }
}
