// swift-tools-version:6.0
import PackageDescription

let packageName = "Reachable"

let package = Package(
    name: packageName,
    platforms: [
        .iOS(.v18),
.macOS(.v15)
    ],
    products: [
        .library(
            name: packageName,
            targets: [packageName]
        ),
    ],
    targets: [
        .binaryTarget(
            name: packageName,
            path: "./reachable/build/XCFrameworks/debug/\(packageName).xcframework"
        )
        ,
    ]
)