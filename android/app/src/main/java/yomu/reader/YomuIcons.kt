package yomu.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

object YomuIcons {
    val Studio by lazy {
        filled(
            "M11 2.5l2.1 5.9L19 10.5l-5.9 2.1L11 18.5l-2.1-5.9L3 10.5l5.9-2.1zM18.5 15l.9 2.6 2.6.9-2.6.9-.9 2.6-.9-2.6-2.6-.9 2.6-.9z",
        )
    }
    val Panels by lazy {
        outlined("M4 4h9.5v7H4zM16.5 4H20v7h-3.5zM4 14h4.5v6H4zM11.5 14H20v6h-8.5z")
    }
    val Translate by lazy {
        outlined("M3 6h10M8 3.5V6M5 6c.7 3.1 2.8 5.6 6 7M11 6c-.7 3.3-3 6-7 7.8M12.5 20.5l4-10 4 10M14 17h5")
    }
    val Enhance by lazy {
        outlined("M4 20L15 9M13 5.5V3M13 12v-1M18.5 8H21M9 8H6.5M16.9 4.1l1.7-1.7M9.4 4.4L7.7 2.7M17 12l1.7 1.7")
    }
    val AutoScroll by lazy {
        outlined("M12 3v14M6.5 11.5L12 17l5.5-5.5M5 21h14")
    }
    val Ambient by lazy {
        outlined(
            "M12 3a9 9 0 1 0 0 18c1.4 0 2.2-1 2.2-2.2 0-1.4-1-1.8-1-3 0-1 .8-1.8 2-1.8h2.3A3.5 3.5 0 0 0 21 10.500C20.2 6.200 16.500 3 12 3zM7.500 12h.01M9.500 7.800h.01M14.500 7.800h.01",
        )
    }
    val Memory by lazy {
        outlined(
            "M5 4.500A1.500 1.500 0 0 1 6.500 3H19v15H6.500A1.500 1.500 0 0 0 5 19.500zM5 19.500A1.500 1.500 0 0 0 6.500 21H19v-3M9 8h6M9 12h4",
        )
    }
    val Play by lazy {
        filled("M8 5.200v13.600a.8.8 0 0 0 1.200.7l11-6.800a.8.8 0 0 0 0-1.400l-11-6.800A.8.8 0 0 0 8 5.200z")
    }
    val Pause by lazy { filled("M7 5h3.500v14H7zM13.500 5H17v14h-3.500z") }
    val Minus by lazy { outlined("M6 12h12") }
    val Plus by lazy { outlined("M12 6v12M6 12h12") }
    val Stop by lazy { outlined("M6 6l12 12M18 6L6 18") }
    val Heart by lazy {
        filled(
            "M12 20.500S3.500 15.600 3.500 9.300A4.800 4.800 0 0 1 12 6.300a4.800 4.800 0 0 1 8.500 3C20.500 15.600 12 20.500 12 20.500z",
        )
    }
    val ForYou by lazy {
        outlined(
            "M7 4h12a1 1 0 0 1 1 1v12M4 8.500A1.500 1.500 0 0 1 5.500 7h9A1.500 1.500 0 0 1 16 8.500v10A1.500 1.500 0 0 1 14.500 20h-9A1.500 1.500 0 0 1 4 18.500z",
        )
    }

    val Send by lazy { outlined("M4 12l16-8-6 16-3-7zM11 13l9-9") }
    val Ask by lazy {
        outlined(
            "M4 5.500h16v11H10l-4.500 3.500v-3.500H4zM9.800 9a2.200 2.200 0 1 1 3.400 1.900c-.8.5-1.200.9-1.200 1.600M12 14.800h.01",
        )
    }
    val Voice by lazy {
        outlined("M4 9.500v5h3.500L12 18.500v-13L7.500 9.500zM15.500 9a4 4 0 0 1 0 6M18 6.500a7.500 7.500 0 0 1 0 11")
    }
    val Bookmark by lazy { outlined("M6.500 3.500h11v17L12 16.500l-5.500 4z") }
    val Swap by lazy { outlined("M4 8h13M13.500 4.500L17 8l-3.500 3.500M20 16H7M10.500 12.500L7 16l3.500 3.500") }

    val Year by lazy { outlined("M5 20V11M12 20V4M19 20v-6") }
    val Volumes by lazy { outlined("M5 4h3.500v16H5zM8.500 4H12v16H8.500zM14 5l3.400-.9 3.600 15-3.400.9z") }
    val Offline by lazy { outlined("M12 3v12M7.500 10.500L12 15l4.500-4.500M5 20.500h14") }
    val Cloud by lazy {
        outlined("M7 18.500a4.500 4.500 0 0 1-.6-9 6 6 0 0 1 11.500 1.600 3.800 3.800 0 0 1-.4 7.400z")
    }

    private fun filled(path: String) = builder("Filled").addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()

    private fun outlined(path: String) = builder("Outlined").addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
}
