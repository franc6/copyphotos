import Foundation

/// How Photos rounds and classifies camera metadata, as numbers and cases. The views format
/// the results for the user's locale ("1/120 s", "ƒ1.78", "12 MP", "4K").
public enum CameraMetadata {
    public enum ShutterSpeed: Equatable, Sendable {
        /// 1/`denominator` of a second.
        case fraction(denominator: Int)
        /// Whole seconds, rounded to one decimal.
        case seconds(Double)
    }

    /// A fraction up to half a second; longer ones in seconds (0.8 s as a fraction would round
    /// to a misleading 1/1 s).
    public static func shutterSpeed(_ seconds: Double) -> ShutterSpeed? {
        guard seconds.isFinite, seconds > 0 else { return nil }
        if seconds <= 0.5 { return .fraction(denominator: Int((1 / seconds).rounded())) }
        return .seconds((seconds * 10).rounded() / 10)
    }

    /// Exposure compensation, rounded to 0.1 EV.
    public static func exposureBias(_ ev: Double) -> Double? {
        guard ev.isFinite else { return nil }
        let rounded = (ev * 10).rounded() / 10
        return rounded == 0 ? 0 : rounded // never "-0"
    }

    /// Megapixels: whole once there are enough of them (12.2 → 12), else one decimal (8.3).
    public static func megapixels(width: Int, height: Int) -> Double? {
        guard width > 0, height > 0 else { return nil }
        let mp = Double(width) * Double(height) / 1_000_000
        return mp >= 10 ? mp.rounded() : (mp * 10).rounded() / 10
    }

    public enum VideoResolution: Equatable, Sendable {
        case uhd8K
        case uhd4K
        /// "1080p", "720p": the short side in lines.
        case lines(Int)
    }

    public static func videoResolution(width: Int, height: Int) -> VideoResolution? {
        let short = min(width, height), long = max(width, height)
        guard short > 0 else { return nil }
        if long >= 7680 || short >= 4320 { return .uhd8K }
        if long >= 3840 || short >= 2160 { return .uhd4K }
        return .lines(short)
    }

    /// A coordinate in degrees, minutes and seconds (seconds to one decimal).
    public struct DMS: Equatable, Sendable {
        public var degrees: Int
        public var minutes: Int
        public var seconds: Double
        /// North or east.
        public var isPositive: Bool
    }

    /// Seconds that round up to 60 carry into the minutes, and minutes into the degrees.
    public static func dms(_ value: Double) -> DMS {
        let a = abs(value)
        var d = Int(a)
        var m = Int((a - Double(d)) * 60)
        var s = (((a - Double(d)) * 60 - Double(m)) * 60 * 10).rounded() / 10
        if s >= 60 { s -= 60; m += 1 }
        if m >= 60 { m -= 60; d += 1 }
        return DMS(degrees: d, minutes: m, seconds: s, isPositive: value >= 0)
    }

    /// The EXIF "OffsetTimeOriginal" string as seconds from GMT.
    /// - Parameter exif: The string: "+02:00", "-05:30" or "Z".
    /// - Returns: The offset, or nil if the string isn't a valid offset.
    public static func timeZoneOffset(_ exif: String) -> Int? {
        let s = exif.trimmingCharacters(in: .whitespaces)
        if s == "Z" { return 0 }
        guard let match = s.wholeMatch(of: #/([+-])(\d\d):(\d\d)/#),
              let h = Int(match.2), let m = Int(match.3), h <= 18, m < 60 else { return nil }
        return (match.1 == "-" ? -1 : 1) * (h * 3600 + m * 60)
    }

    /// "Apple iPhone 15 Pro": the make is left out when the model already starts with it, and
    /// starts with a capital (some makers write "samsung").
    public static func cameraName(make: String?, model: String?) -> String? {
        func clean(_ s: String?) -> String? {
            let t = s?.trimmingCharacters(in: .whitespacesAndNewlines.union(.controlCharacters)) ?? ""
            return t.isEmpty ? nil : t
        }
        let make = clean(make).map { $0.prefix(1).uppercased() + $0.dropFirst() }
        switch (make, clean(model)) {
        case let (make?, model?) where model.lowercased().hasPrefix(make.lowercased()): return model
        case let (make?, model?): return "\(make) \(model)"
        case let (make, model): return model ?? make
        }
    }

    /// Which parts of a reverse-geocoded address name a photo's place, most specific first:
    /// the town (or else its district), then the region (or else the country). With neither,
    /// the place's own name (a landmark, or the sea). The views join the parts.
    public static func placeNameParts(locality: String?, subAdministrativeArea: String?,
                                      administrativeArea: String?, country: String?, name: String?) -> [String] {
        var parts: [String] = []
        for part in [locality ?? subAdministrativeArea, administrativeArea ?? country].compactMap({ $0 })
        where !part.isEmpty && !parts.contains(part) {
            parts.append(part)
        }
        if parts.isEmpty, let name, !name.isEmpty { parts = [name] }
        return parts
    }
}
