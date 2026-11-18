import Foundation

/// Whether a square thumbnail should show the whole image instead of its cropped centre: when
/// that centre is nearly one flat colour (e.g. a screenshot of a mostly empty screen), the
/// cropped tile would look blank. The views sample the centre square on a `grid` × `grid` raster.
public enum ThumbnailFit {
    /// Samples per side of the centre square.
    public static let grid = 16

    /// Perceived brightness (Rec. 601) of an 8-bit colour.
    public static func luma(red: Int, green: Int, blue: Int) -> Double {
        0.299 * Double(red) + 0.587 * Double(green) + 0.114 * Double(blue)
    }

    /// True when the samples' brightness barely varies (standard deviation below `threshold`).
    public static func looksFlat(_ lumas: [Double], threshold: Double = 6) -> Bool {
        guard !lumas.isEmpty else { return false }
        let n = Double(lumas.count)
        let mean = lumas.reduce(0, +) / n
        let variance = lumas.reduce(0) { $0 + $1 * $1 } / n - mean * mean
        return max(0, variance).squareRoot() < threshold
    }
}
