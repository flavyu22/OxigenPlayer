// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

/**
 * The full flavor links the GPLv3 NextLib/FFmpeg software decoders, whose notice must be
 * conveyed with the binary. The resource lives only in this source set, which is why the
 * lookup is flavor-specific rather than a BuildConfig branch in shared code.
 */
internal object CodecLicenseNotice {
    val rawResourceId: Int? = R.raw.nextlib_notice
}
