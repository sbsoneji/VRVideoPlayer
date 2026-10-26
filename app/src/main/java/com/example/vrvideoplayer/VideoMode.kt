package com.example.vrvideoplayer

/** How the pixels of a video are laid out in the headset. */
enum class VideoMode {
    /** Normal 2D video on a virtual cinema screen (both eyes see the same image). */
    FLAT_2D,

    /** Side-by-side 3D video on a virtual screen (left half -> left eye, right half -> right eye). */
    FLAT_SBS_3D,

    /** Top-bottom 3D video on a virtual screen (top half -> left eye, bottom half -> right eye). */
    FLAT_TB_3D,

    /** 360-degree equirectangular video painted on a sphere around the viewer. */
    SPHERE_360;

    companion object {
        private val TOP_BOTTOM = Regex("(^|[^a-z])(tb|htb|ftb)([^a-z]|$)")

        /** Best-effort guess from a file name or URL, used to pre-select the right mode. */
        fun guessFrom(name: String): VideoMode {
            val n = name.lowercase()
            return when {
                // "360p" is a resolution label, not a 360-degree video.
                "360" in n && "360p" !in n -> SPHERE_360
                "top-bottom" in n || "topbottom" in n || "over-under" in n ||
                    "overunder" in n || TOP_BOTTOM.containsMatchIn(n) -> FLAT_TB_3D
                "sbs" in n || "side-by-side" in n || "sidebyside" in n || "3d" in n -> FLAT_SBS_3D
                else -> FLAT_2D
            }
        }
    }
}
