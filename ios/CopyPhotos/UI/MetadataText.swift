import CopyCore
import Foundation

/// Camera metadata as text in the user's locale, the way the Photos app's Info panel shows it
/// ("1/120 s", "ƒ1.78", "12 MP", "4K"). The rules (rounding, thresholds) come from
/// `CameraMetadata`; numbers, measurements and durations are formatted by the system, and the
/// unit words come from Localizable.xcstrings.
enum MetadataText {
    /// Up to `decimals` decimals, without trailing zeros.
    private static func number(_ value: Double, decimals: Int) -> String {
        value.formatted(.number.precision(.fractionLength(0...decimals)))
    }

    static func exposureTime(_ seconds: Double) -> String? {
        switch CameraMetadata.shutterSpeed(seconds) {
        case .fraction(let d)?: return String(localized: "1/\(d) s", comment: "Shutter speed as a fraction of a second")
        case .seconds(let s)?: return String(localized: "\(number(s, decimals: 1)) s", comment: "Shutter speed in seconds")
        case nil: return nil
        }
    }

    static func fNumber(_ value: Double) -> String? {
        guard value.isFinite, value > 0 else { return nil }
        return String(localized: "ƒ\(number(value, decimals: 2))", comment: "Aperture")
    }

    static func focalLength(_ mm: Double) -> String? {
        guard mm.isFinite, mm > 0 else { return nil }
        return Measurement(value: mm, unit: UnitLength.millimeters)
            .formatted(.measurement(width: .abbreviated, usage: .asProvided,
                                    numberFormatStyle: .number.precision(.fractionLength(0...2))))
    }

    static func iso(_ value: Int) -> String {
        String(localized: "ISO \(value)", comment: "Sensor sensitivity")
    }

    static func exposureBias(_ ev: Double) -> String? {
        guard let rounded = CameraMetadata.exposureBias(ev) else { return nil }
        let n = rounded.formatted(.number.precision(.fractionLength(0...1)).sign(strategy: .always(includingZero: false)))
        return String(localized: "\(n) ev", comment: "Exposure compensation")
    }

    static func megapixels(width: Int, height: Int) -> String? {
        guard let mp = CameraMetadata.megapixels(width: width, height: height) else { return nil }
        return String(localized: "\(number(mp, decimals: 1)) MP", comment: "Megapixels")
    }

    static func videoResolution(width: Int, height: Int) -> String? {
        switch CameraMetadata.videoResolution(width: width, height: height) {
        case .uhd8K?: return String(localized: "8K", comment: "Video resolution")
        case .uhd4K?: return String(localized: "4K", comment: "Video resolution")
        case .lines(let n)?: return String(localized: "\(n)p", comment: "Video resolution, e.g. 1080p")
        case nil: return nil
        }
    }

    static func frameRate(_ fps: Double) -> String? {
        guard fps.isFinite, fps > 0 else { return nil }
        return String(localized: "\(number(fps, decimals: 2)) fps", comment: "Video frame rate")
    }

    /// "0:07", "12:03", "1:02:03".
    static func duration(_ seconds: Double) -> String? {
        guard seconds.isFinite, seconds >= 0 else { return nil }
        let total = Int(seconds.rounded())
        return Duration.seconds(total).formatted(.time(pattern: total >= 3600 ? .hourMinuteSecond : .minuteSecond))
    }

    /// 37°19′54.2″ N, 122°01′51.6″ W.
    static func coordinate(latitude: Double, longitude: Double) -> String {
        func part(_ value: Double, positive: String, negative: String) -> String {
            let p = CameraMetadata.dms(value)
            let minutes = p.minutes.formatted(.number.precision(.integerLength(2)).grouping(.never))
            let seconds = p.seconds.formatted(.number.precision(.integerAndFractionLength(integer: 2, fraction: 1)))
            return String(localized: "\(p.degrees)°\(minutes)′\(seconds)″ \(p.isPositive ? positive : negative)",
                          comment: "One coordinate: degrees, minutes, seconds, then the hemisphere letter")
        }
        let lat = part(latitude, positive: String(localized: "Coordinate.North", defaultValue: "N", comment: "North"),
                       negative: String(localized: "Coordinate.South", defaultValue: "S", comment: "South"))
        let lon = part(longitude, positive: String(localized: "Coordinate.East", defaultValue: "E", comment: "East"),
                       negative: String(localized: "Coordinate.West", defaultValue: "W", comment: "West"))
        return String(localized: "\(lat), \(lon)", comment: "Latitude, longitude")
    }
}
