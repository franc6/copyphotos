// swift-tools-version:5.9
import PackageDescription

// CopyCore is the app's model layer, with nothing that depends on UIKit, PhotoKit or SMB:
// the remote-filesystem abstraction, the copy/verify pipeline, the ledger, the copy
// service, the destination config, and the browser and result models. Keeping it
// platform-free lets it be unit-tested on macOS with `swift test`.
let package = Package(
    name: "CopyCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "CopyCore", targets: ["CopyCore"]),
    ],
    targets: [
        .target(name: "CopyCore"),
        .testTarget(name: "CopyCoreTests", dependencies: ["CopyCore"]),
    ]
)
