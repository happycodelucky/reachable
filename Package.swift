// swift-tools-version:6.0
import PackageDescription

// BEGIN KMMBRIDGE VARIABLES BLOCK (do not edit)
let remoteKotlinUrl = "https://github.com/happycodelucky/reachable/releases/download/v0.13.0/Reachable.xcframework.zip"
let remoteKotlinChecksum = "9c2992290156e6005f3681e36f62eba82e536599296780574d278a6b53266709"
let packageName = "Reachable"
// END KMMBRIDGE BLOCK

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
            url: remoteKotlinUrl,
            checksum: remoteKotlinChecksum
        )
        ,
    ]
)