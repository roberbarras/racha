package io.github.roberbarras.racha.desktop.tools

import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.nio.file.Files
import java.nio.file.Path

/** Rasterises an SVG to a square PNG with Skia (ImageMagick's policy blocks SVG): `RenderIconKt <in.svg> <out.png> <size>`. */
fun main(args: Array<String>) {
    val (input, output, size) = Triple(Path.of(args[0]), Path.of(args[1]), args[2].toInt())
    val svg = SVGDOM(Data.makeFromBytes(Files.readAllBytes(input)))
    svg.setContainerSize(size.toFloat(), size.toFloat())
    val surface = Surface.makeRasterN32Premul(size, size)
    svg.render(surface.canvas)
    val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG) ?: error("encoding failed")
    Files.write(output, png.bytes)
}
