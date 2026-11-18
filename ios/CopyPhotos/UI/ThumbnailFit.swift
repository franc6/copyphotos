import CopyCore
import UIKit

/// Whether a square tile should show `image` whole, scaled to fit, instead of its cropped
/// centre: samples the centre square and applies `ThumbnailFit.looksFlat` (e.g. a screenshot of
/// a mostly empty screen would otherwise make a blank-looking tile).
func cropLooksBlank(_ image: UIImage) -> Bool {
    let grid = ThumbnailFit.grid
    guard let cg = image.cgImage else { return false }
    let side = min(cg.width, cg.height)
    guard side >= 2,
          let square = cg.cropping(to: CGRect(x: (cg.width - side) / 2, y: (cg.height - side) / 2, width: side, height: side)),
          let ctx = CGContext(data: nil, width: grid, height: grid, bitsPerComponent: 8, bytesPerRow: grid * 4,
                              space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
          let data = ctx.data else { return false }
    ctx.interpolationQuality = .low
    ctx.draw(square, in: CGRect(x: 0, y: 0, width: grid, height: grid))
    let px = data.bindMemory(to: UInt8.self, capacity: grid * grid * 4)
    let lumas = (0..<(grid * grid)).map { i in
        ThumbnailFit.luma(red: Int(px[i * 4]), green: Int(px[i * 4 + 1]), blue: Int(px[i * 4 + 2]))
    }
    return ThumbnailFit.looksFlat(lumas)
}
