package com.androidcamera.webcam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import com.androidcamera.webcam.data.PreferencesRepository
import com.androidcamera.webcam.model.AvailableCamera
import com.androidcamera.webcam.model.CameraFacing

/**
 * Enumerates cameras actually present on the device.
 *
 * Many multi-lens phones expose only the logical front/back IDs via
 * [CameraManager.getCameraIdList], while ultra-wide / tele / macro appear as
 * physical IDs (or OEM aux IDs). We surface every colour camera that
 * [CameraManager.getCameraCharacteristics] can describe, and record which
 * logical CameraX camera should host a physical ID.
 */
object CameraInventory {

    data class CameraBinding(
        val camera: AvailableCamera,
        /** CameraX/Camera2 id to open (logical when [physicalId] is set). */
        val openId: String,
        /** Optional physical camera forced via Camera2Interop. */
        val physicalId: String? = null,
        /**
         * Whether this camera has a flash unit we can drive via FLASH_MODE.
         * Probed at binding time from [CameraCharacteristics.FLASH_INFO_AVAILABLE].
         * False for OEM aux / ultrawide / tele lenses that have no LED.
         * The UI / camera controllers MUST consult this flag before pushing
         * FLASH_MODE: setting it on a lens with no flash unit is rejected by
         * the firmware and can freeze the capture session.
         */
        val hasFlash: Boolean = false,
    )

    @Volatile
    private var cachedBindings: List<CameraBinding>? = null

    fun listCameras(context: Context): List<AvailableCamera> =
        listBindings(context).map { it.camera }

    fun listBindings(context: Context): List<CameraBinding> {
        cachedBindings?.let { return it }
        val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        val publicIds = manager.cameraIdList.toList()
        val physicalOf = mutableMapOf<String, String>() // physicalId -> logicalId

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (logicalId in publicIds) {
                val physical = runCatching {
                    manager.getCameraCharacteristics(logicalId).physicalCameraIds
                }.getOrNull().orEmpty()
                for (pid in physical) {
                    physicalOf.putIfAbsent(pid, logicalId)
                }
                if (physical.isNotEmpty()) {
                    Log.i(TAG, "Logical camera $logicalId physicalIds=$physical")
                }
            }
        }

        val candidateIds = linkedSetOf<String>()
        candidateIds.addAll(publicIds)
        candidateIds.addAll(physicalOf.keys)
        for (extra in probeExtraIds(manager, candidateIds)) {
            candidateIds += extra
        }

        data class Raw(
            val id: String,
            val facing: CameraFacing,
            val focal: Float?,
            val fromPublicList: Boolean,
            val hasFlash: Boolean,
        )

        val raw = mutableListOf<Raw>()
        for (id in candidateIds) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
            if (chars == null) {
                Log.w(TAG, "Skipping camera id=$id (characteristics unavailable)")
                continue
            }
            if (!supportsColourOutput(chars)) {
                Log.i(TAG, "Skipping camera id=$id (no colour output)")
                continue
            }
            val facing = facingOf(chars.get(CameraCharacteristics.LENS_FACING))
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull()
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            raw += Raw(id, facing, focal, fromPublicList = id in publicIds, hasFlash = hasFlash)
            Log.i(
                TAG,
                "Found camera id=$id facing=$facing focalMm=$focal " +
                    "public=${id in publicIds} hasFlash=$hasFlash"
            )
        }

        // Drop OEM alias IDs that mirror a public logical camera (same facing + focal),
        // e.g. Xiaomi 100/101 often duplicate 0/1.
        val publicKeys = raw.filter { it.fromPublicList }
            .map { facingFocalKey(it.facing, it.focal) }
            .toSet()
        val deduped = raw.filter { entry ->
            if (entry.fromPublicList) return@filter true
            if (entry.id in physicalOf) return@filter true
            val key = facingFocalKey(entry.facing, entry.focal)
            val keep = key !in publicKeys
            if (!keep) Log.i(TAG, "Skipping alias camera id=${entry.id} key=$key")
            keep
        }

        val ordered = deduped.sortedWith(
            compareBy(
                { facingSortKey(it.facing) },
                { idSortKey(it.id) }
            )
        )

        val counters = mutableMapOf<CameraFacing, Int>()
        val bindings = ordered.map { entry ->
            val index = (counters[entry.facing] ?: 0) + 1
            counters[entry.facing] = index
            val camera = AvailableCamera(
                id = entry.id,
                facing = entry.facing,
                indexAmongFacing = index,
                focalLengthMm = entry.focal
            )
            val logicalHost = physicalOf[entry.id]
            if (logicalHost != null && logicalHost != entry.id) {
                CameraBinding(
                    camera = camera,
                    openId = logicalHost,
                    physicalId = entry.id,
                    hasFlash = entry.hasFlash,
                )
            } else {
                CameraBinding(
                    camera = camera,
                    openId = entry.id,
                    physicalId = null,
                    hasFlash = entry.hasFlash,
                )
            }
        }
        cachedBindings = bindings
        return bindings
    }

    fun resolveBinding(
        context: Context,
        preferences: PreferencesRepository
    ): CameraBinding? {
        val bindings = listBindings(context)
        if (bindings.isEmpty()) return null

        preferences.selectedCameraId?.let { id ->
            bindings.firstOrNull { it.camera.id == id }?.let { return it }
        }

        val legacy = preferences.legacyCameraFacing()
        val migrated = when (legacy) {
            "FRONT" -> bindings.firstOrNull { it.camera.facing == CameraFacing.FRONT }
            "BACK", "BOTH", null -> bindings.firstOrNull { it.camera.facing == CameraFacing.BACK }
            else -> null
        } ?: bindings.firstOrNull { it.camera.facing == CameraFacing.BACK } ?: bindings.first()

        preferences.selectedCameraId = migrated.camera.id
        return migrated
    }

    fun resolve(context: Context, preferences: PreferencesRepository): AvailableCamera? =
        resolveBinding(context, preferences)?.camera

    private fun probeExtraIds(
        manager: CameraManager,
        already: Set<String>
    ): List<String> {
        val found = mutableListOf<String>()
        val suspects = (2..32).map { it.toString() } +
            listOf("100", "101", "102", "103")
        for (id in suspects) {
            if (id in already) continue
            val ok = runCatching { manager.getCameraCharacteristics(id) }.isSuccess
            if (ok) {
                found += id
                Log.i(TAG, "Discovered extra camera id=$id via probe")
            }
        }
        return found
    }

    private fun supportsColourOutput(chars: CameraCharacteristics): Boolean {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return true
        return caps.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
        )
    }

    private fun facingFocalKey(facing: CameraFacing, focal: Float?): String {
        val f = focal?.let { String.format("%.2f", it) } ?: "?"
        return "$facing|$f"
    }

    private fun facingOf(lensFacing: Int?): CameraFacing = when (lensFacing) {
        CameraCharacteristics.LENS_FACING_BACK -> CameraFacing.BACK
        CameraCharacteristics.LENS_FACING_FRONT -> CameraFacing.FRONT
        CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraFacing.EXTERNAL
        else -> CameraFacing.UNKNOWN
    }

    private fun facingSortKey(facing: CameraFacing): Int = when (facing) {
        CameraFacing.BACK -> 0
        CameraFacing.FRONT -> 1
        CameraFacing.EXTERNAL -> 2
        CameraFacing.UNKNOWN -> 3
    }

    private fun idSortKey(id: String): Long =
        id.toLongOrNull() ?: Long.MAX_VALUE

    private const val TAG = "CameraInventory"
}
